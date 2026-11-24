package org.symphonykernel.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.symphonykernel.eval.EvaluationReport.Result;
import org.symphonykernel.eval.EvaluationReport.Status;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

/** Executes a validated suite sequentially through a caller-supplied application adapter. */
public final class EvaluationRunner {
    private static final String EXACT = "exact";
    private static final String CONTAINS = "contains";

    private final AnswerProvider provider;
    private final Map<String, Scorer> scorers;
    private final String fallbackJudgeModel;

    public EvaluationRunner(AnswerProvider provider) {
        this(provider, Map.of());
    }

    public EvaluationRunner(AnswerProvider provider, Map<String, Scorer> customScorers) {
        this(provider, customScorers, null);
    }

    EvaluationRunner(AnswerProvider provider, Map<String, Scorer> customScorers, String fallbackJudgeModel) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.fallbackJudgeModel = fallbackJudgeModel == null || fallbackJudgeModel.isBlank()
                ? null : fallbackJudgeModel.strip();
        Map<String, Scorer> registry = new HashMap<>();
        registry.put(EXACT, (request, expected, actual) -> match(expected.isTextual() && actual.isTextual()
                ? expected.textValue().strip().equals(actual.textValue().strip()) : expected.equals(actual)));
        registry.put(CONTAINS, (request, expected, actual) -> match(actual.isTextual()
                && actual.textValue().contains(expected.textValue().strip())));
        customScorers.forEach((name, scorer) -> {
            requireText(name, "Scorer name");
            if (registry.putIfAbsent(name, Objects.requireNonNull(scorer, "scorer")) != null) {
                throw new IllegalArgumentException("Scorer already registered: " + name);
            }
        });
        scorers = Map.copyOf(registry);
    }

    /** Checks suite settings against the registered scorers without calling the application. */
    public void validate(EvaluationSuite suite) {
        prepare(suite);
    }

    /** Returns all case results. Invalid suite settings fail before any application calls. */
    public EvaluationReport run(EvaluationSuite suite) throws InterruptedException {
        List<PreparedCase> prepared = prepare(suite);
        List<Result> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        double sum = 0;
        for (PreparedCase item : prepared) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Evaluation interrupted");
            }
            Result result = evaluate(item);
            results.add(result);
            if (result.status() == Status.PASS) {
                passed++;
            } else if (result.status() == Status.FAIL) {
                failed++;
            }
            if (result.score() != null) {
                sum += result.score();
            }
        }
        int total = results.size();
        int scored = passed + failed;
        return new EvaluationReport(suite.version(), new EvaluationReport.Summary(total, passed, failed,
                total - scored, (double) passed / total, scored == 0 ? null : sum / scored), results);
    }

    private List<PreparedCase> prepare(EvaluationSuite suite) {
        if (suite == null || suite.version() != 1 || suite.cases() == null || suite.cases().isEmpty()) {
            throw new IllegalArgumentException("Expected a version 1 suite with at least one case");
        }
        EvaluationSuite.Defaults defaults = suite.defaults() == null
                ? new EvaluationSuite.Defaults(null, null, null, null) : suite.defaults();
        Set<String> ids = new HashSet<>();
        List<PreparedCase> prepared = new ArrayList<>();
        for (EvaluationSuite.Case item : suite.cases()) {
            if (item == null) {
                throw new IllegalArgumentException("Null case in suite");
            }
            String id = requireText(item.id(), "Case id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate case id: " + id);
            }
            String prefix = "Case " + id + ": ";
            String question = requireText(item.question(), prefix + "question");
            String model = requireText(choose(item.model(), choose(defaults.model(), "server-default")), prefix + "model");
            String inferredScoring = nonBlank(item.rubric()) == null ? EXACT : LlmJudgeScorer.NAME;
            String scoring = choose(item.scoring(), choose(defaults.scoring(), inferredScoring));
            if (!scorers.containsKey(scoring)) {
                throw new IllegalArgumentException(prefix + "unknown scoring strategy: " + scoring);
            }
            Double configuredThreshold = choose(item.passThreshold(), defaults.passThreshold());
            double threshold = configuredThreshold == null ? 1.0 : configuredThreshold;
            validateScore(threshold, prefix + "passThreshold");
            String judgeModel = choose(item.judgeModel(), defaults.judgeModel());
            if (LlmJudgeScorer.NAME.equals(scoring)) {
                judgeModel = choose(nonBlank(item.judgeModel()), choose(nonBlank(defaults.judgeModel()), fallbackJudgeModel));
            }
            var request = new Request(id, question, model);
            scorers.get(scoring).validate(new ScoringRequest(request, judgeModel, item.rubric()), threshold);
            JsonNode expected = item.expectedAnswer();
            if (expected == null || expected.isMissingNode()) {
                throw new IllegalArgumentException(prefix + "expectedAnswer is required");
            }
            if (CONTAINS.equals(scoring) && (!expected.isTextual() || expected.textValue().isBlank())) {
                throw new IllegalArgumentException(prefix + "contains requires a non-blank text expectedAnswer");
            }
            String pointer = choose(item.answerPointer(), "");
            if ((!pointer.isEmpty() && !pointer.startsWith("/")) || pointer.matches("(?s).*~(?:[^01]|$).*")) {
                throw new IllegalArgumentException(prefix + "answerPointer is not a valid JSON Pointer: " + pointer);
            }
                prepared.add(new PreparedCase(request, scoring, threshold,
                    JsonPointer.compile(pointer), expected.deepCopy(), judgeModel,
                    item.rubric()));
        }
        return prepared;
    }

    private Result evaluate(PreparedCase item) throws InterruptedException {
        long start = System.nanoTime();
        JsonNode actual = null;
        try {
            JsonNode response = provider.answer(item.request());
            if (response == null || response.isMissingNode()) {
                throw new IllegalStateException("Application adapter returned no answer");
            }
            JsonNode selected = response.at(item.pointer());
            if (selected.isMissingNode()) {
                return result(item, null, new Score(0, "Answer field not found: " + item.pointer()), Status.FAIL,
                        start);
            }
            actual = selected.deepCopy();
            Score score = scorers.get(item.scoring()).score(new ScoringRequest(item.request(), item.judgeModel(),
                    item.rubric()), item.expected().deepCopy(), actual.deepCopy());
            Objects.requireNonNull(score, "Scorer returned no score");
            return result(item, actual, score, score.value() >= item.threshold() ? Status.PASS : Status.FAIL, start);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception error) {
            // Exception messages can contain credentials or request payloads.
            String phase = actual == null ? "Application execution" : "Scoring";
            return result(item, actual, new Score(0, phase + " failed (" + error.getClass().getName() + ")"),
                    Status.ERROR, start);
        }
    }

    private static Result result(PreparedCase item, JsonNode actual, Score score, Status status, long start) {
        return new Result(item.request().caseId(), item.request().question(), item.request().model(), item.scoring(),
                item.judgeModel(), item.expected(), actual, status == Status.ERROR ? null : score.value(),
                item.threshold(), status, score.detail(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static Score match(boolean matches) {
        return new Score(matches ? 1 : 0, matches ? "Matched expected answer" : "Did not match expected answer");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void validateScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private static <T> T choose(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record PreparedCase(Request request, String scoring, double threshold, JsonPointer pointer,
                                JsonNode expected, String judgeModel, String rubric) { }

    public record Request(String caseId, String question, String model) { }

    public record ScoringRequest(Request answerRequest, String judgeModel, String rubric) { }

    public record Score(double value, String detail) {
        public Score {
            validateScore(value, "Score");
        }
    }

    /** Calls the system under test. Expected answers are deliberately not passed to this adapter. */
    @FunctionalInterface
    public interface AnswerProvider {
        JsonNode answer(Request request) throws Exception;
    }

    /** Compares the expected value to the selected application answer. */
    @FunctionalInterface
    public interface Scorer {
        Score score(ScoringRequest request, JsonNode expected, JsonNode actual) throws Exception;

        /** Optional offline validation, performed before any application calls. */
        default void validate(ScoringRequest request, double threshold) {
            // Most scorers have no additional configuration requirements.
        }
    }
}