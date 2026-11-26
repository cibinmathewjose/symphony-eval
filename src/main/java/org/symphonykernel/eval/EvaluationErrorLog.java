package org.symphonykernel.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Per-invocation diagnostics. Never copies payloads or arbitrary exception messages. */
final class EvaluationErrorLog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrintStream err;
    private Path directory = Path.of(".eval-logs");
    private Path file;
    private boolean unavailable;

    EvaluationErrorLog(PrintStream err) {
        this.err = err;
    }

    void setWorkspace(Path workspace) {
        directory = workspace.resolve("logs");
    }

    void error(String phase, String suite, Exception error) {
        var entry = JSON.createObjectNode().put("time", Instant.now().toString()).put("phase", phase)
                .put("suiteId", suite).put("exception", error.getClass().getName());
        if (error instanceof JsonProcessingException invalid && invalid.getLocation() != null) {
            entry.put("line", invalid.getLocation().getLineNr()).put("column", invalid.getLocation().getColumnNr());
        }
        if (error instanceof MissingJudgeConfiguration) {
            entry.put("detail", error.getMessage());
        }
        write(entry);
    }

    void record(EvaluationWorkspace.Run run) {
        for (var result : run.report().cases()) {
            if (result.status() == EvaluationReport.Status.PASS) {
                continue;
            }
            var entry = JSON.createObjectNode().put("time", Instant.now().toString()).put("phase", "case")
                    .put("suiteId", run.metadata().suiteId()).put("runId", run.metadata().runId().toString())
                    .put("caseId", result.id()).put("status", result.status().name()).put("scoring", result.scoring())
                    .put("threshold", result.passThreshold());
            if (result.status() == EvaluationReport.Status.ERROR) {
                entry.put("detail", result.detail());
            } else {
                // A scorer can put sensitive data in its explanation.
                entry.put("score", result.score()).put("detail", "Score below threshold or answer field missing");
            }
            write(entry);
        }
    }

    private void write(com.fasterxml.jackson.databind.node.ObjectNode entry) {
        if (unavailable) {
            return;
        }
        try {
            boolean first = file == null;
            if (first) {
                Files.createDirectories(directory);
                file = Files.createTempFile(directory, "evaluation-errors-", ".log");
            }
            Files.writeString(file, JSON.writeValueAsString(entry) + "\n", StandardOpenOption.APPEND);
            if (first) {
                err.println("Evaluation error log: " + file.toAbsolutePath());
            }
        } catch (IOException | SecurityException failed) {
            unavailable = true;
            err.println("Could not write evaluation error log (" + failed.getClass().getSimpleName() + ")");
        }
    }

    static final class MissingJudgeConfiguration extends IllegalArgumentException {
        MissingJudgeConfiguration() {
            super("Set SYMPHONY_EVAL_JUDGE_URL or supply --config with judge.url before running llm-judge cases");
        }
    }
}