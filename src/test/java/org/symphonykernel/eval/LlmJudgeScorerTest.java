package org.symphonykernel.eval;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class LlmJudgeScorerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void environmentOverridesPropertiesAndCredentialsAreRejected() throws IOException {
        var config = Files.writeString(temp.resolve("eval.properties"), "judge.url=https://judge.example.com/chat/completions\n");
        assertEquals("https://judge.example.com/chat/completions", EvaluationCli.configuredJudgeUrl(config, " "));
        assertEquals("https://override.example.com/chat/completions",
                EvaluationCli.configuredJudgeUrl(config, "https://override.example.com/chat/completions"));
        Files.writeString(config, "judge.token=must-not-be-read\n");
        var error = assertThrows(IllegalArgumentException.class, () -> EvaluationCli.configuredJudgeUrl(config, null));
        assertFalse(error.getMessage().contains("must-not-be-read"));
    }

    @Test
    void selectsJudgePerCaseAndArchivesValidatedVerdicts() throws Exception {
        var target = new LinkedBlockingQueue<JsonNode>();
        var judge = new LinkedBlockingQueue<JsonNode>();
        var auth = new LinkedBlockingQueue<String>();
        var verdicts = new LinkedBlockingQueue<String>();
        verdicts.add("{\"score\":1,\"criticalFailure\":false,\"detail\":\"Complete\"}");
        verdicts.add("{\"score\":1,\"criticalFailure\":true,\"detail\":\"Missing a required fact\"}");
        verdicts.add("{\"score\":\"1\",\"criticalFailure\":false,\"detail\":\"Invalid score type\"}");
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/answer", exchange -> {
            try (exchange) {
                target.add(JSON.readTree(exchange.getRequestBody()));
                reply(exchange, JSON.getNodeFactory().textNode("candidate"));
            }
        });
        server.createContext("/judge", exchange -> {
            try (exchange) {
                judge.add(JSON.readTree(exchange.getRequestBody()));
                auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                var envelope = JSON.createObjectNode();
                envelope.putArray("choices").addObject().put("finish_reason", "stop")
                        .putObject("message").put("content", verdicts.remove());
                reply(exchange, envelope);
            }
        });
        server.createContext("/rejected", exchange -> {
            try (exchange) {
                byte[] bytes = "private-provider-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var sink = new PrintStream(OutputStream.nullOutputStream())) {
            var source = Files.writeString(temp.resolve("suite.yaml"), """
                    version: 1
                    defaults: {model: target-model, judgeModel: default-judge, scoring: llm-judge}
                    cases:
                      - {id: one, question: q, expectedAnswer: reference, rubric: Require all facts}
                      - {id: two, question: q, expectedAnswer: reference, judgeModel: override-judge}
                      - {id: three, question: q, expectedAnswer: reference}
                      - {id: four, question: q, expectedAnswer: candidate, scoring: exact}
                    """);
            String root = temp.resolve("workspace").toString();
            String base = "http://localhost:" + server.getAddress().getPort();
            assertEquals(0, EvaluationCli.execute(new String[]{root, "add", "suite", source.toString()}, sink, sink,
                    null, null, null));
            String[] run = {root, "run", "suite", base + "/answer"};
            assertEquals(2, EvaluationCli.execute(run, sink, sink, null, null, null));
            assertEquals(0, target.size());
            var config = Files.writeString(temp.resolve("judge settings.properties"), "judge.url=" + base + "/judge\n");
            String[] configuredRun = {"--config", config.toString(), root, "run", "suite", base + "/answer"};
            assertEquals(1, EvaluationCli.execute(configuredRun, sink, sink, null, null, "judge-token", "environment-judge"));
            var archive = new EvaluationWorkspace(Path.of(root));
            var results = archive.getRun("suite", archive.history("suite").get(0).runId()).report().cases();
            assertEquals(java.util.List.of("PASS", "FAIL", "ERROR", "PASS"),
                    results.stream().map(item -> item.status().name()).toList());
            assertEquals(0.0, results.get(1).score());
            assertEquals(4, target.size());
            assertFalse(target.remove().has("expectedAnswer"));
            assertEquals(3, judge.size());
            var first = judge.remove();
            assertEquals("default-judge", first.path("model").asText());
            assertEquals("override-judge", judge.remove().path("model").asText());
            assertEquals("Bearer judge-token", auth.remove());
            var data = JSON.readTree(first.at("/messages/1/content").asText());
            assertEquals("reference", data.path("reference").asText());
            assertEquals("candidate", data.path("candidate").asText());
            assertEquals("Require all facts", data.path("rubric").asText());
            assertEquals(1, EvaluationCli.execute(run, sink, sink, null, base + "/rejected", "judge-token"));
            var entries = new java.util.ArrayList<JsonNode>();
            try (var logs = Files.list(Path.of(root, "logs"))) {
                for (Path log : logs.toList()) {
                    String text = Files.readString(log);
                    assertFalse(text.contains("judge-token"));
                    assertFalse(text.contains("private-provider-response"));
                    assertFalse(text.contains("Missing a required fact"));
                    for (String line : text.lines().toList()) {
                        entries.add(JSON.readTree(line));
                    }
                }
            }
            assertTrue(entries.stream().anyMatch(entry -> entry.path("phase").asText().equals("run validation")
                    && entry.path("detail").asText().contains("SYMPHONY_EVAL_JUDGE_URL")));
            assertTrue(entries.stream().anyMatch(entry -> entry.path("caseId").asText().equals("three")
                    && entry.path("detail").asText().contains("Invalid judge verdict")));
            assertTrue(entries.stream().anyMatch(entry -> entry.path("caseId").asText().equals("one")
                    && entry.path("detail").asText().contains("Judge returned HTTP 429")));

                var fallbackSource = Files.writeString(temp.resolve("fallback.yaml"), """
                    version: 1
                    cases:
                      - {id: judged, question: q, expectedAnswer: reference, rubric: Require all facts}
                      - {id: deterministic, question: q, expectedAnswer: candidate, rubric: '  '}
                      - {id: explicit-exact, question: q, expectedAnswer: candidate, scoring: exact, rubric: Ignored}
                    """);
                String[] addFallback = {root, "add", "fallback", fallbackSource.toString()};
                assertEquals(2, EvaluationCli.execute(addFallback, sink, sink, null, null, null, " "));
                assertEquals(0, EvaluationCli.execute(addFallback, sink, sink, null, null, null, "environment-judge"));
                judge.clear();
                verdicts.add("{\"score\":1,\"criticalFailure\":false,\"detail\":\"Complete\"}");
                assertEquals(0, EvaluationCli.execute(new String[]{root, "run", "fallback", base + "/answer"},
                    sink, sink, null, base + "/judge", null, " environment-judge "));
                assertEquals(1, judge.size());
                assertEquals("environment-judge", judge.remove().path("model").asText());
                var fallbackRun = archive.getRun("fallback", archive.history("fallback").get(0).runId());
                assertEquals("environment-judge", fallbackRun.report().cases().get(0).judgeModel());
                assertEquals("server-default", fallbackRun.report().cases().get(0).model());
                org.junit.jupiter.api.Assertions.assertNull(fallbackRun.report().cases().get(1).judgeModel());

                    var exactSource = Files.writeString(temp.resolve("exact.yaml"), """
                        version: 1
                        defaults: {model: target-model, scoring: exact}
                        cases:
                          - {id: inherited-exact, question: q, expectedAnswer: candidate, rubric: Ignored}
                        """);
                    assertEquals(0, EvaluationCli.execute(new String[]{root, "add", "exact", exactSource.toString()},
                        sink, sink, null, null, null, null));
                    assertEquals(0, EvaluationCli.execute(new String[]{root, "run", "exact", base + "/answer"},
                        sink, sink, null, null, null, null));
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange exchange, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}