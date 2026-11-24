package org.symphonykernel.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class EvaluationBatchTest {
    @TempDir
    Path temp;

    @Test
    void runsEveryRegisteredDemoSuiteAndReportsFailures() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new LinkedBlockingQueue<JsonNode>();
        var authHeaders = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stream", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
                var bytes = "data: {\"eventType\":\"content\",\"content\":\"yes\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {
            var source = temp.resolve("suite.yaml");
            var root = temp.resolve("archive");
            var report = temp.resolve("batch.json");
                String yaml = """
                    version: 1
                    defaults: {model: server-default}
                    cases:
                      - {id: case-1, question: "<script> & test", expectedAnswer: "%s"}
                    """;
            for (String id : new String[]{"yes", "no"}) {
                Files.writeString(source, yaml.formatted(id));
                assertEquals(0, EvaluationCli.execute(new String[]{root.toString(), "add", id, source.toString()},
                        out, out, null));
            }
            var address = server.getAddress();
            var endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(),
                    "/stream", null, null);
            var batch = new String[]{root.toString(), "run-all", "sse", endpoint.toString(), report.toString()};
            var html = temp.resolve("batch.html");
            Files.writeString(html, "existing report");
            assertEquals(2, EvaluationCli.execute(batch, out, out, null));
            assertEquals("existing report", Files.readString(html));
            assertFalse(Files.exists(report));
            assertTrue(requests.isEmpty());
            Files.delete(html);
            assertEquals(1, EvaluationCli.execute(batch, out, out, null, null, null, null, "X-API-Key", "test-secret"));
            assertEquals(2, EvaluationCli.execute(batch, out, out, null));
            String rendered = Files.readString(html);
            assertTrue(rendered.contains("Symphony Evaluation Report"));
            assertTrue(rendered.contains("&lt;script&gt; &amp; test"));
            assertFalse(rendered.contains("<script>"));
            assertTrue(rendered.contains(">PASS</span>"));
            assertTrue(rendered.contains(">FAIL</span>"));
            var result = mapper.readTree(report.toFile());
            assertEquals("sse", result.path("adapter").asText());
            assertEquals("test-secret", authHeaders.remove());
            assertEquals("test-secret", authHeaders.remove());
            assertFalse(result.path("successful").asBoolean());
            assertEquals("FAIL", result.at("/runs/0/report/cases/0/status").asText());
            assertEquals("PASS", result.at("/runs/1/report/cases/0/status").asText());
            assertEquals(1, new EvaluationWorkspace(root).history("yes").size());
            assertEquals(2, requests.size());
            var first = requests.remove();
            assertEquals("<script> & test", first.path("message").asText());
            UUID.fromString(first.path("sessionId").asText());
            assertFalse(first.has("expectedAnswer"));
        } finally {
            server.stop(0);
        }
    }
}