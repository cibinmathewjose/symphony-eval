package org.symphonykernel.eval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class DemoStreamingAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EvaluationRunner.Request INPUT = new EvaluationRunner.Request("case-1", "Question?", "model");
    private static final String CONTENT = "data: {\"schemaVersion\":\"1.0\",\"eventType\":\"content\",\"content\":\"partial\"}\n\n";

    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var address = server.getAddress();
        endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/stream", null, null);
        server.start();
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
    }

    @Test
    void postsChatRequestAndReadsPastIntermediateCompletion() throws Exception {
        var requests = new LinkedBlockingQueue<JsonNode>();
        server.createContext("/stream", exchange -> {
            try (exchange) {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                startStream(exchange);
                write(exchange, ": heartbeat\r\n\r\ndata: {\"schemaVersion\":\"1.0\",\"eventType\":\"status\"}\r\n\r\n");
                write(exchange, "data: {\"schemaVersion\":\"1.0\",\r\ndata: \"eventType\":\"content\",\"content\":\"Résultat\"}\r\n\r\n");
                write(exchange, "data: {\"schemaVersion\":\"1.0\",\"eventType\":\"complete\",\"stage\":\"flow_item\"}\n\n");
                write(exchange, "data: {\"eventType\":\"content\",\"content\":\" final\"}\n\n");
            }
        });
        var adapter = adapter(Duration.ofSeconds(5));
        assertEquals("Résultat final", adapter.answer(INPUT).asText());
        assertEquals("Résultat final", adapter.answer(INPUT).asText());
        var request = requests.remove();
        assertEquals("Question?", request.path("message").asText());
        assertEquals(2, request.size());
        String session = request.path("sessionId").asText();
        assertEquals(session, UUID.fromString(session).toString());
        assertNotEquals(session, requests.remove().path("sessionId").asText());
        assertFalse(request.has("expectedAnswer"));
    }

    @Test
    void rejectsErrorsAndUnfinishedFramesInsteadOfReturningPartialText() throws Exception {
        serve(CONTENT + "data: {\"schemaVersion\":\"1.0\",\"eventType\":\"error\"}\n\n");
        var adapter = adapter(Duration.ofSeconds(5));
        assertEquals("StreamErrorException", assertThrows(IOException.class, () -> adapter.answer(INPUT))
                .getClass().getSimpleName());
        server.removeContext("/stream");
        serve(CONTENT + "data: {\"schemaVersion\":\"1.0\",\"eventType\":\"content\"");
        assertEquals("InvalidStreamException", assertThrows(IOException.class, () -> adapter.answer(INPUT))
                .getClass().getSimpleName());
        server.removeContext("/stream");
        serve(CONTENT + "data: {\"schemaVersion\":\"1.0\",\"eventType\":\"complete\",\"stage\":\"human_loop\"}\n\n");
        assertEquals("AwaitingInputException", assertThrows(IOException.class, () -> adapter.answer(INPUT))
                .getClass().getSimpleName());
    }

    @Test
    void enforcesDeadlineAfterResponseHeaders() throws Exception {
        var sent = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/stream", exchange -> {
            try (exchange) {
                startStream(exchange);
                write(exchange, CONTENT);
                sent.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var timeout = assertThrows(HttpTimeoutException.class, () -> adapter(Duration.ofSeconds(2)).answer(INPUT));
            assertTrue(timeout.getMessage().contains("deadline"));
            assertEquals(0, sent.getCount());
        } finally {
            release.countDown();
        }
    }

    private DemoStreamingAdapter adapter(Duration timeout) {
        return new DemoStreamingAdapter(endpoint, timeout);
    }

    private void serve(String body) {
        server.createContext("/stream", exchange -> {
            try (exchange) {
                startStream(exchange);
                write(exchange, body);
            }
        });
    }

    private static void startStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void write(HttpExchange exchange, String chunk) throws IOException {
        exchange.getResponseBody().write(chunk.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
}