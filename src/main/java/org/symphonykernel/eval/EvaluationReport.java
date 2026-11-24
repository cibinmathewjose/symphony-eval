package org.symphonykernel.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Machine-readable results in suite order. Error cases have no score. */
public record EvaluationReport(int version, Summary summary, List<Result> cases) {
    public EvaluationReport {
        cases = List.copyOf(cases);
    }

    public boolean successful() {
        return summary.failed() == 0 && summary.errors() == 0;
    }

    public void writeJson(Path output) throws IOException {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), this);
    }

    public enum Status { PASS, FAIL, ERROR }

    public record Summary(int total, int passed, int failed, int errors, double passRate, Double meanScore) { }

    public record Result(String id, String question, String model, String scoring, String judgeModel,
                         JsonNode expectedAnswer, JsonNode actualAnswer, Double score, double passThreshold,
                         Status status, String detail, long durationMs) { }
}