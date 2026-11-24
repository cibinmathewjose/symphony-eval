package org.symphonykernel.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpServer;

class EvaluationWorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void registersRevisesAndPersistsCompletedRuns() throws Exception {
        var root = temp.resolve("archive");
        var workspace = new EvaluationWorkspace(root);
        var calls = new AtomicInteger();
        var runner = new EvaluationRunner(input -> {
            calls.incrementAndGet();
            return TextNode.valueOf("30 days");
        });
        var source = writeSuite("30 days");
        assertTrue(workspace.listSuites().isEmpty());
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> workspace.addSuite("../escape", source, runner)).getMessage().startsWith("Suite ID"));
        workspace.addSuite("refunds", source, runner);
        assertTrue(assertThrows(FileAlreadyExistsException.class,
            () -> workspace.addSuite("refunds", source, runner)).getFile().endsWith("suite-refunds.yaml"));
        assertEquals(0, calls.get());
        assertEquals(List.of("refunds"), workspace.listSuites());

        var passed = workspace.execute("refunds", runner);
        assertTrue(passed.report().successful());
        workspace.updateSuite("refunds", writeSuite("60 days"), runner);
        var failed = workspace.execute("refunds", runner);
        assertFalse(failed.report().successful());
        assertNotEquals(passed.metadata().suiteSha256(), failed.metadata().suiteSha256());

        var reopened = new EvaluationWorkspace(root);
        assertEquals(List.of(failed.metadata(), passed.metadata()), reopened.history("refunds"));
        assertEquals(passed, reopened.getRun("refunds", passed.metadata().runId()));
        assertEquals(failed, reopened.getRun("refunds", failed.metadata().runId()));
        assertTrue(reopened.history("other").isEmpty());
    }

    @Test
    void cliRunsAgainstAnApplicationAndShowsHistoryWithoutAnotherRequest() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new LinkedBlockingQueue<JsonNode>();
        var authHeaders = new LinkedBlockingQueue<String>();
        var customHeaders = new LinkedBlockingQueue<String>();
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/evaluate", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                customHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
                byte[] answer = "\"30 days\"".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, answer.length);
                exchange.getResponseBody().write(answer);
            }
        });
        http.start();
        try (var buffer = new ByteArrayOutputStream(); var out = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            String root = temp.resolve("cli").toString();
                var address = http.getAddress();
                String url = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(),
                    "/evaluate", null, null).toString();
            var source = writeSuite("30 days");
            var suite = new YamlEvaluationSuiteLoader().load(source);
            assertEquals(0, EvaluationCli.execute(new String[]{root, "add", "refunds", source.toString()},
                    out, out, null));
            assertEquals(0, EvaluationCli.execute(new String[]{root, "run", "refunds", url}, out, out, "test-token"));
            assertEquals(0, EvaluationCli.execute(new String[]{root, "history", "refunds"}, out, out, null));
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("100.0% pass"));
                var run = new EvaluationWorkspace(Path.of(root)).history("refunds").get(0);
                buffer.reset();
                assertEquals(0, EvaluationCli.execute(new String[]{root, "show", "refunds", run.runId().toString()},
                    out, out, null));
                assertEquals("30 days", mapper.readTree(buffer.toByteArray()).at("/report/cases/0/actualAnswer").asText());
            assertEquals(1, requests.size());
            var request = requests.remove();
            assertEquals(suite.cases().get(0).id(), request.path("caseId").asText());
            assertEquals(suite.cases().get(0).question(), request.path("question").asText());
            assertEquals(suite.defaults().model(), request.path("model").asText());
            assertTrue(request.path("sessionId").asText().startsWith("eval-"));
            assertFalse(request.has("expectedAnswer"));
            assertEquals("Bearer test-token", authHeaders.remove());
                assertEquals("null", customHeaders.remove());

                String[] batch = {root, "run-all", "json", url, temp.resolve("custom-report.json").toString()};
                assertEquals(2, EvaluationCli.execute(batch, out, out, null, null, null, null, "X-API-Key", null));
                assertTrue(requests.isEmpty());
                assertEquals(0, EvaluationCli.execute(batch, out, out, "legacy-token", null, null, null,
                    "X-API-Key", "Test value=123"));
                assertEquals("Test value=123", customHeaders.remove());
                assertEquals("null", authHeaders.remove());
        } finally {
            http.stop(0);
        }
    }

    private Path writeSuite(String answer) throws Exception {
        return Files.writeString(temp.resolve("source.yaml"), """
                version: 1
                defaults:
                  model: test-model
                cases: [{id: sample-case, question: "What is the configured value?", expectedAnswer: "%s"}]
                """.formatted(answer));
    }
}