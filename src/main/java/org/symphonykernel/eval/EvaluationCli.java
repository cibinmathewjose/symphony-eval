package org.symphonykernel.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Standalone command line for JSON and Symphony Demo streaming evaluations. */
public final class EvaluationCli {
    private static final String JUDGE_URL = "SYMPHONY_EVAL_JUDGE_URL";
    private static final String JUDGE_MODEL = "SYMPHONY_EVAL_JUDGE_MODEL";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String USAGE = """
            Usage: java -jar <cli-jar> [--config <properties-file>] <workspace> <command> [arguments]
              add <suite-id> <source-yaml>      Register a new suite
              update <suite-id> <source-yaml>   Replace suite; keep history
              list                             List registered suites
              run <suite-id> <endpoint-url>    Execute and archive a suite
              run-all <sse|json> <endpoint-url> <report-json>
                                               Run all suites and write JSON and HTML reports
              history <suite-id>               Show run summaries, newest first
              show <suite-id> <run-id>         Print one detailed run as JSON
            SYMPHONY_EVAL_TOKEN: optional bearer token for target requests
            SYMPHONY_EVAL_HEADER_KEY and SYMPHONY_EVAL_HEADER_VALUE: custom auth header for target requests
            LLM judge: SYMPHONY_EVAL_JUDGE_URL (or judge.url in config) and optional SYMPHONY_EVAL_JUDGE_TOKEN
            SYMPHONY_EVAL_JUDGE_MODEL: default model for llm-judge cases without a YAML judgeModel
            """;

    private EvaluationCli() { }

    public static void main(String[] args) {
        int status = execute(args, System.out, System.err, System.getenv("SYMPHONY_EVAL_TOKEN"));
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(String[] args, PrintStream out, PrintStream err, String token) {
        return execute(args, out, err, token, System.getenv(JUDGE_URL), System.getenv("SYMPHONY_EVAL_JUDGE_TOKEN"));
    }

    static int execute(String[] args, PrintStream out, PrintStream err, String token, String judgeUrl, String judgeToken) {
        return execute(args, out, err, token, judgeUrl, judgeToken, System.getenv(JUDGE_MODEL));
    }

    static int execute(String[] args, PrintStream out, PrintStream err, String token, String judgeUrl, String judgeToken,
                       String judgeModel) {
        return execute(args, out, err, token, judgeUrl, judgeToken, judgeModel,
            System.getenv("SYMPHONY_EVAL_HEADER_KEY"), System.getenv("SYMPHONY_EVAL_HEADER_VALUE"));
        }

        static int execute(String[] args, PrintStream out, PrintStream err, String token, String judgeUrl, String judgeToken,
                   String judgeModel, String headerKey, String headerValue) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.print(USAGE);
            return 0;
        }
        try {
            if (args.length > 0 && "--config".equals(args[0])) {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Expected --config <properties-file> followed by a workspace and command");
                }
                judgeUrl = configuredJudgeUrl(Path.of(args[1]), judgeUrl);
                args = Arrays.copyOfRange(args, 2, args.length);
            }
            if (args.length < 2) {
                throw new IllegalArgumentException("Expected a workspace and command");
            }
            var workspace = new EvaluationWorkspace(Path.of(args[0]));
            return switch (args[1]) {
                case "add", "update" -> {
                    requireCount(args, 4);
                    var validator = new EvaluationRunner(request -> {
                        throw new IllegalStateException("No application adapter configured for registration");
                    }, Map.of(LlmJudgeScorer.NAME, new JudgeConfigurationCheck(true)), judgeModel);
                    if ("add".equals(args[1])) {
                        workspace.addSuite(args[2], Path.of(args[3]), validator);
                    } else {
                        workspace.updateSuite(args[2], Path.of(args[3]), validator);
                    }
                    out.println("Saved suite: " + args[2]);
                    yield 0;
                }
                case "list" -> {
                    requireCount(args, 2);
                    workspace.listSuites().forEach(out::println);
                    yield 0;
                }
                case "run" -> {
                    requireCount(args, 4);
                    var provider = httpProvider(args[3], token, headerKey, headerValue);
                    var run = workspace.execute(args[2], runner(provider, judgeUrl, judgeToken, judgeModel));
                    printSummary(out, run.metadata());
                    yield run.report().successful() ? 0 : 1;
                }
                case "history" -> {
                    requireCount(args, 3);
                    var runs = workspace.history(args[2]);
                    if (runs.isEmpty()) {
                        out.println("No runs for suite: " + args[2]);
                    }
                    runs.forEach(run -> printSummary(out, run));
                    yield 0;
                }
                case "run-all" -> {
                    requireCount(args, 5);
                    var provider = switch (args[2]) {
                        case "json" -> httpProvider(args[3], token, headerKey, headerValue);
                        case "sse", "demo-stream" -> {
                            URI endpoint = URI.create(args[3]);
                            yield new DemoStreamingAdapter(endpoint, REQUEST_TIMEOUT,
                                    targetAuthentication(endpoint, token, headerKey, headerValue));
                        }
                        default -> throw new IllegalArgumentException("Unknown adapter: " + args[2]);
                    };
                    yield runAll(workspace, runner(provider, judgeUrl, judgeToken, judgeModel), args[2], Path.of(args[4]), out, err);
                }
                case "show" -> {
                    requireCount(args, 4);
                    var run = workspace.getRun(args[2], UUID.fromString(args[3]));
                    out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run));
                    yield 0;
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[1]);
            };
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IOException ex) {
            err.println("Evaluation workspace error: " + ex.getMessage());
            return 2;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            err.println("Evaluation interrupted");
            return 2;
        }
    }

    static String configuredJudgeUrl(Path file, String environmentUrl) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        if (!properties.stringPropertyNames().stream().allMatch("judge.url"::equals)) {
            throw new IllegalArgumentException("Config supports only judge.url; supply credentials via environment variables");
        }
        return environmentUrl == null || environmentUrl.isBlank() ? properties.getProperty("judge.url") : environmentUrl;
    }

    private static EvaluationRunner runner(EvaluationRunner.AnswerProvider provider, String url, String token,
                                           String judgeModel) {
        EvaluationRunner.Scorer judge = url == null || url.isBlank() ? new JudgeConfigurationCheck(false)
                : new LlmJudgeScorer(URI.create(url), token, REQUEST_TIMEOUT);
        return new EvaluationRunner(provider, Map.of(LlmJudgeScorer.NAME, judge), judgeModel);
    }

    private record JudgeConfigurationCheck(boolean registrationOnly) implements EvaluationRunner.Scorer {
        @Override
        public void validate(EvaluationRunner.ScoringRequest request, double threshold) {
            LlmJudgeScorer.validateCase(request, threshold);
            if (!registrationOnly) {
                throw new IllegalArgumentException("Set SYMPHONY_EVAL_JUDGE_URL or supply --config with judge.url before running llm-judge cases");
            }
        }

        @Override
        public EvaluationRunner.Score score(EvaluationRunner.ScoringRequest request,
                com.fasterxml.jackson.databind.JsonNode expected, com.fasterxml.jackson.databind.JsonNode actual) {
            throw new IllegalStateException("No judge client configured");
        }
    }

    private static int runAll(EvaluationWorkspace workspace, EvaluationRunner runner, String adapter, Path output,
                              PrintStream out, PrintStream err) throws IOException, InterruptedException {
        List<String> ids = workspace.listSuites();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No registered suites; add a suite before running evaluations");
        }
        Path html = HtmlEvaluationReport.companionPath(output);
        // Reserve both outputs before sending any cases; never overwrite existing files.
        Files.createFile(output);
        boolean htmlReserved = false;
        boolean written = false;
        try {
            Files.createFile(html);
            htmlReserved = true;
            String startedAt = Instant.now().toString();
            List<EvaluationWorkspace.Run> runs = new ArrayList<>();
            List<SuiteError> errors = new ArrayList<>();
            for (String id : ids) {
                try {
                    var run = workspace.execute(id, runner);
                    runs.add(run);
                    printSummary(out, run.metadata());
                } catch (IOException | IllegalArgumentException failed) {
                    errors.add(new SuiteError(id, failed.getClass().getSimpleName()));
                    err.println("Could not complete suite: " + id);
                }
            }
            int status = !errors.isEmpty() ? 2 : runs.stream().allMatch(run -> run.report().successful()) ? 0 : 1;
                var report = new BatchReport(1, adapter, startedAt, Instant.now().toString(), status == 0, runs, errors);
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
                Files.writeString(html, HtmlEvaluationReport.render(report));
            written = true;
            out.println("Batch report: " + output.toAbsolutePath());
                out.println("HTML report: " + html.toAbsolutePath());
            return status;
        } finally {
            if (!written) {
                Files.deleteIfExists(output);
                if (htmlReserved) {
                    Files.deleteIfExists(html);
                }
            }
        }
    }

    record SuiteError(String suiteId, String errorType) { }

    record BatchReport(int version, String adapter, String startedAt, String finishedAt, boolean successful,
                               List<EvaluationWorkspace.Run> runs, List<SuiteError> suiteErrors) { }

    private static EvaluationRunner.AnswerProvider httpProvider(String endpoint, String token,
                                                                 String headerKey, String headerValue) {
        URI uri = URI.create(endpoint);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Expected an HTTP(S) endpoint without user info or fragment");
        }
        var authentication = targetAuthentication(uri, token, headerKey, headerValue);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return input -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("caseId", input.caseId(), "question", input.question(),
                "model", input.model(), "sessionId", "eval-" + UUID.randomUUID()));
            var request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            authentication.accept(request);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException();
            }
            return JSON.readTree(response.body());
        };
        }

        private static java.util.function.Consumer<HttpRequest.Builder> targetAuthentication(
            URI uri, String token, String headerKey, String headerValue) {
        boolean hasKey = headerKey != null && !headerKey.isBlank();
        boolean hasValue = headerValue != null && !headerValue.isBlank();
        if (hasKey != hasValue) {
            throw new IllegalArgumentException("Set both SYMPHONY_EVAL_HEADER_KEY and SYMPHONY_EVAL_HEADER_VALUE");
        }
        if (hasKey) {
            try {
                if ("Accept".equalsIgnoreCase(headerKey) || "Content-Type".equalsIgnoreCase(headerKey)) {
                    throw new IllegalArgumentException();
                }
                HttpRequest.newBuilder(uri).header(headerKey, headerValue);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid or reserved target header name or invalid header value");
            }
            return request -> request.header(headerKey, headerValue);
        }
        return request -> {
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
        };
    }

    private static void requireCount(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException("Wrong number of arguments for " + args[1]);
        }
    }

    private static void printSummary(PrintStream out, EvaluationWorkspace.RunSummary run) {
        var summary = run.summary();
        out.printf(Locale.ROOT, "%s  %s  %s  rev=%s  %.1f%% pass  %d passed / %d failed / %d errors%n",
                run.startedAt(), run.suiteId(), run.runId(), run.suiteSha256().substring(0, 12),
                summary.passRate() * 100, summary.passed(), summary.failed(), summary.errors());
    }

    private static final class HttpStatusException extends IOException { }
}