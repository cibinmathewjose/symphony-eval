package org.symphonykernel.eval;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/** Evaluates one-shot requests against the Symphony Demo chat SSE endpoint. */
public final class DemoStreamingAdapter implements EvaluationRunner.AnswerProvider {
    private static final String SSE = "text/event-stream";
    private static final int MAX_STREAM_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final long timeoutNanos;
    private final Consumer<HttpRequest.Builder> authentication;

    public DemoStreamingAdapter(URI endpoint, Duration timeout) {
        this(endpoint, timeout, request -> { });
    }

    DemoStreamingAdapter(URI endpoint, Duration timeout, Consumer<HttpRequest.Builder> authentication) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Expected an HTTP(S) endpoint without user info or fragment");
        }
        this.timeoutNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    @Override
    public JsonNode answer(EvaluationRunner.Request input) throws IOException, InterruptedException {
        var payload = JSON.createObjectNode().put("sessionId", UUID.randomUUID().toString())
                .put("message", input.question());
        var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json").header("Accept", SSE);
        authentication.accept(request);
        var exchange = client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(
            JSON.writeValueAsBytes(payload))).build(), info -> {
            int status = info.statusCode();
            if (status < 200 || status >= 300) {
                throw new UncheckedIOException(new HttpStatusException());
            }
            String mediaType = info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            if (!SSE.equalsIgnoreCase(mediaType)) {
                throw new UncheckedIOException(new InvalidStreamException());
            }
            return new BoundedBody();
        });
        try {
            return readAnswer(exchange.get(timeoutNanos, TimeUnit.NANOSECONDS).body());
        } catch (TimeoutException timeout) {
            exchange.cancel(true);
            var failure = new HttpTimeoutException("Demo stream did not finish before the deadline");
            failure.initCause(timeout);
            throw failure;
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            throw new IOException("Demo streaming request failed", failed);
        }
    }

    private static JsonNode readAnswer(byte[] body) throws IOException {
        StringBuilder answer = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String event = "";
        try (var reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                StandardCharsets.UTF_8.newDecoder()))) {
            boolean firstLine = true;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (firstLine && line.startsWith("\ufeff")) {
                    line = line.substring(1);
                }
                firstLine = false;
                if (line.isEmpty()) {
                    if ("error".equals(event)) {
                        throw new StreamErrorException();
                    }
                    if (!data.isEmpty()) {
                        appendContent(JSON.readTree(data.toString()), answer);
                        data.setLength(0);
                    }
                    event = "";
                } else if (line.startsWith("data:") || "data".equals(line)) {
                    data.append(fieldValue(line)).append('\n');
                } else if (line.startsWith("event:") || "event".equals(line)) {
                    event = fieldValue(line);
                }
            }
        }
        if (!data.isEmpty() || "error".equals(event) || answer.isEmpty()) {
            throw new InvalidStreamException();
        }
        return TextNode.valueOf(answer.toString());
    }

    private static String fieldValue(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String value = line.substring(colon + 1);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static void appendContent(JsonNode envelope, StringBuilder answer) throws IOException {
        if (envelope == null || !envelope.isObject()
                || (envelope.has("schemaVersion") && !"1.0".equals(envelope.path("schemaVersion").asText()))
                || !envelope.path("eventType").isTextual()) {
            throw new InvalidStreamException();
        }
        String type = envelope.path("eventType").asText().toLowerCase(Locale.ROOT);
        boolean humanLoop = "human_loop".equalsIgnoreCase(envelope.path("stage").asText());
        if ("error".equals(type)) {
            throw new StreamErrorException();
        }
        if (humanLoop && "complete".equals(type)) {
            throw new AwaitingInputException();
        }
        if ("content".equals(type)) {
            JsonNode content = envelope.path("content");
            if (!content.isTextual()) {
                throw new InvalidStreamException();
            }
            if (humanLoop) {
                JsonNode state = JSON.readTree(content.textValue());
                if (state != null && "AWAITING_INPUT".equalsIgnoreCase(state.path("status").asText())) {
                    throw new AwaitingInputException();
                }
            }
            answer.append(content.textValue());
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_STREAM_BYTES - body.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new StreamTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                body.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }

    private static final class HttpStatusException extends IOException { }
    private static final class InvalidStreamException extends IOException { }
    private static final class StreamErrorException extends IOException { }
    private static final class AwaitingInputException extends IOException { }
    private static final class StreamTooLargeException extends IOException { }
}