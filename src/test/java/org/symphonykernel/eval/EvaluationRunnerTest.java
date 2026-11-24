package org.symphonykernel.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.symphonykernel.eval.EvaluationReport.Status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

class EvaluationRunnerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final YamlEvaluationSuiteLoader loader = new YamlEvaluationSuiteLoader();

    @TempDir
    Path temp;

    @Test
    void runsExampleWithDefaultAndOverriddenModelsAndWritesReport() throws Exception {
        var suite = loader.load(Path.of("examples/smoke.yaml"));
        var responses = json.readTree(Path.of("src/test/resources/smoke-responses.json").toFile()).elements();
        List<EvaluationRunner.Request> requests = new ArrayList<>();
        var runner = new EvaluationRunner(request -> {
            requests.add(request);
            return responses.next();
        });
        var report = runner.run(suite);

        assertTrue(report.successful());
        assertFalse(responses.hasNext());
        assertEquals(suite.cases().stream().map(item -> new EvaluationRunner.Request(item.id(), item.question(),
                item.model() == null ? suite.defaults().model() : item.model())).toList(), requests);
        assertEquals(suite.cases().size(), report.summary().passed());
        assertEquals(1.0, report.summary().meanScore());
        Path output = temp.resolve("result.json");
        report.writeJson(output);
        var serialized = json.readTree(output.toFile());
        assertEquals(report, json.treeToValue(serialized, EvaluationReport.class));
    }

    @Test
    void separatesWrongAnswersFromErrorsAndContinues() throws Exception {
        var suite = load("""
                version: 1
                defaults: {model: target}
                cases:
                  - {id: adapter-error, question: q, expectedAnswer: ok}
                  - {id: missing, question: q, expectedAnswer: null, answerPointer: /missing}
                  - {id: judge-error, question: q, expectedAnswer: ok, scoring: invalid-score}
                  - {id: explicit-null, question: q, expectedAnswer: null, answerPointer: /present}
                  - {id: contains, question: q, expectedAnswer: days, scoring: contains}
                """);
        var responses = List.<EvaluationRunner.AnswerProvider>of(
            request -> { throw new IOException("sensitive response payload"); },
            request -> json.readTree("{\"present\":null}"),
            request -> TextNode.valueOf("ok"),
            request -> json.readTree("{\"present\":null}"),
            request -> TextNode.valueOf("several days")).iterator();
        var runner = new EvaluationRunner(request -> responses.next().answer(request),
            Map.of("invalid-score", (request, expected, actual) -> new EvaluationRunner.Score(Double.NaN, "bad")));

        var report = runner.run(suite);
        assertFalse(report.successful());
        assertEquals(List.of(Status.ERROR, Status.FAIL, Status.ERROR, Status.PASS, Status.PASS),
                report.cases().stream().map(EvaluationReport.Result::status).toList());
        assertEquals(2, report.summary().errors());
        assertEquals(0.4, report.summary().passRate());
        assertEquals(2.0 / 3, report.summary().meanScore());
        assertNull(report.cases().get(0).score());
        assertFalse(report.cases().get(0).detail().contains("sensitive"));
        assertTrue(report.cases().get(3).actualAnswer().isNull());
    }

    @Test
    void supportsCustomRubricsAndCaseThresholds() throws Exception {
        var suite = load("""
                version: 1
                defaults: {model: target, scoring: rubric, judgeModel: judge, passThreshold: 0.9}
                cases:
                  - id: partial
                    question: Expand the abbreviation.
                    expectedAnswer: Alpha Beta
                    passThreshold: 0.75
                    rubric: Include both expanded words.
                """);
        var runner = new EvaluationRunner(request -> TextNode.valueOf("Alpha"),
                Map.of("rubric", (request, expected, actual) -> {
                    assertEquals(suite.cases().get(0).rubric(), request.rubric());
                    assertEquals(suite.defaults().judgeModel(), request.judgeModel());
                    assertEquals(suite.defaults().model(), request.answerRequest().model());
                    return new EvaluationRunner.Score(0.75, "Close enough");
                }));
        var result = runner.run(suite).cases().get(0);
        assertEquals(Status.PASS, result.status());
        assertEquals(0.75, result.score());
        assertEquals("Close enough", result.detail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id: good", "scoring: unknown", "passThreshold: 1.1", "answerPointer: /bad~2",
            "model: ' '"})
    void validatesEveryCaseBeforeCallingApplication(String invalidProperty) throws Exception {
        var suite = load("""
                version: 1
                defaults: {model: target}
                cases:
                  - {id: good, question: q, expectedAnswer: a}
                  - {%squestion: q, expectedAnswer: a, %s}
                """.formatted(invalidProperty.startsWith("id:") ? "" : "id: bad, ", invalidProperty));
        var runner = new EvaluationRunner(request -> { throw new AssertionError("Application must not be called"); });
        var error = assertThrows(IllegalArgumentException.class, () -> runner.run(suite));
        assertTrue(error.getMessage().contains(invalidProperty.split(":", 2)[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{id: a, question: q}", "{id: a, question: q, expectedAnswer: x, unknown: y}",
            "{id: a, id: b, question: q, expectedAnswer: x}"})
    void rejectsMalformedCaseConfiguration(String item) {
        var error = assertThrows(IOException.class, () -> load("version: 1\ncases:\n  - " + item));
        assertTrue(error.getMessage().contains("line:"));
    }

    @Test
    void interruptionStopsTheSuite() throws Exception {
        var suite = loader.load(Path.of("examples/smoke.yaml"));
        var attempts = new AtomicInteger();
        var runner = new EvaluationRunner(request -> {
            attempts.incrementAndGet();
            throw new InterruptedException("cancelled");
        });
        var error = assertThrows(InterruptedException.class, () -> runner.run(suite));
        assertEquals("cancelled", error.getMessage());
        assertEquals(1, attempts.get());
    }

    private EvaluationSuite load(String yaml) throws IOException {
        return loader.load(Files.writeString(temp.resolve("suite.yaml"), yaml));
    }
}