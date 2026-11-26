# Symphony Eval

Standalone Java 17 evaluation framework for applications in any language. It has no Symphony runtime, Spring runtime, or model-provider dependency. The application owns its knowledge and use-case configuration; the evaluator sends questions through the application's normal answer path and scores the results.

## Standalone CLI

### Portable Windows launcher

From the launcher folder, run `.\run-eval.cmd` in PowerShell or `run-eval` in Command Prompt, with no arguments. The launcher discovers YAML suites in `eval` and loads `.env` beside the launcher, regardless of the caller's working directory. It preserves history in `.eval-workspace` and writes timestamped JSON and HTML reports under `.eval-workspace/reports`. The default target is the local Symphony Demo SSE endpoint on port 8085; start Symphony Demo separately before using it. Explicit workspace and command arguments still work; `--eval-dir <folder>` can also be used by itself to override discovery for a default run.

After `mvn package`, copy the CLI JAR, [run-eval.cmd](scripts/run-eval.cmd), and [run-eval.ps1](scripts/run-eval.ps1) into one folder on the other machine. Keep the JAR's built filename. Java 17+ must be on `PATH`, and Windows PowerShell must be allowed to run the launcher script under the machine's policy. Copy your evaluation workspace or register the suites again on that machine.

For Azure OpenAI judging, provision a private `.env` beside the launcher with `AZURE_OPENAI_ENDPOINT` (the HTTPS resource root) and `AZURE_OPENAI_API_KEY`. Do not commit or bundle the credential. The launcher reads the file at startup, forms the v1 Chat Completions URL, and passes the settings to Java without changing your terminal or machine environment. If either judge environment variable is already set, the launcher does not import the dotenv pair. Without the dotenv file the launcher still works for deterministic scoring, or with judge settings supplied explicitly. The JAR itself does not read dotenv files.

Run from the deployment folder, or use absolute paths for workspace and report arguments:

```bat
run-eval.cmd .eval-demo run-all demo-stream http://localhost:8085/directchat/stream demo-report.json
```

To authenticate SSE or JSON target API calls with a custom header, set both values in the private `.env` beside the launcher:

```dotenv
SYMPHONY_EVAL_HEADER_KEY=X-API-Key
SYMPHONY_EVAL_HEADER_VALUE=replace-with-your-secret
```

The custom pair replaces `SYMPHONY_EVAL_TOKEN` for target requests, and the value is sent without adding a prefix. If the key is `Authorization` and the server expects bearer authentication, include `Bearer ` in the value. If either variable is set in the process environment, set both there; the launcher does not mix values from different sources. The pair is not sent to the LLM judge. Use HTTPS and do not commit the secret.

SSE is the no-argument default. Set `SYMPHONY_EVAL_ENDPOINT` to use a different SSE URL, or leave it unset for the local Demo on port 8085. Set `SYMPHONY_EVAL_ADAPTER=json` to opt into the JSON API instead. The SSE adapter posts JSON with `sessionId` (a fresh UUID) and `message` (the question), and reads `text/event-stream` frames whose JSON `data` contains `eventType: "content"` and text `content`. `demo-stream` remains an alias for `sse`.

Put suite files in an `eval` folder **beside the launcher**. Before `list`, `run`, or `run-all`, the launcher registers or updates top-level `.yaml` and `.yml` files in that folder. The filename without its extension is the suite ID (for example, `demo-inventory.yaml` becomes `demo-inventory`). Use lowercase IDs and avoid duplicate stems. A registration error stops the command before any evaluation requests are sent. Existing run history is preserved; removing a file from the folder does not unregister it. If the folder is absent or empty, the launcher uses the existing workspace as-is.

Override the discovery folder with `--eval-dir` as the first launcher option (before `--config`, if used):

```bat
run-eval.cmd --eval-dir "C:\QA Suites" .eval-qa run-all demo-stream http://localhost:8085/directchat/stream qa-report.json
```

Relative override paths resolve from the calling directory. An explicit folder must exist. The override changes where the launcher discovers YAML, not which suites are already registered. `run-all` runs every registered suite in the selected workspace, so use a separate workspace if you want to keep the suite sets and histories separate. The launcher forwards the normal CLI arguments and exit code. Use a fresh report filename for each batch run.

Build the Maven project with the `package` goal and run the attached `cli` classifier JAR. The first argument is a local workspace directory; use a separate directory per application and environment. The following commands show a complete workflow (replace `<cli-jar>` and `<run-id>`):

```text
java -jar <cli-jar> .eval-workspace add refunds examples/smoke.yaml
java -jar <cli-jar> .eval-workspace list
java -jar <cli-jar> .eval-workspace run refunds https://test.example.com/evaluate
java -jar <cli-jar> .eval-workspace history refunds
java -jar <cli-jar> .eval-workspace show refunds <run-id>
java -jar <cli-jar> .eval-workspace update refunds examples/smoke.yaml
```

Create a suite per use case, or group related use cases into one suite. `add` validates the YAML without calling the app. `update` changes what future runs execute without discarding history. Each completed run is archived, including failures and case errors. `history` shows pass rate, counts, timestamp and a short suite revision; `show` returns the full JSON with case-level results. Compare runs with the same suite revision for a like-for-like view. The revision is a SHA-256 hash of the exact YAML bytes; it does not identify application or knowledge versions. The CLI can be scheduled in CI and returns `0` for success, `1` for failed/error cases, or `2` for invocation, configuration, or workspace errors.

Add questions, expected answers, and scoring settings in YAML, not Java. The CLI sends each question to the configured API and never dispatches on the case ID. The Maven unit tests exercise the evaluator itself using canned responses; they do not evaluate application knowledge or call an LLM.

The target endpoint can be an existing API or a small adapter. By default, the `json` adapter sends a JSON `POST` with `caseId`, `question`, `model`, and a fresh `sessionId`. Use `requestBody` below if the API expects a different shape. Expected answers are never sent automatically. Route the request through the configured application use case (for example via a use-case-specific endpoint or the normal question router) and return the final answer as JSON: a quoted string, a structured value, or a response envelope selected by `answerPointer`. Non-2xx and invalid JSON responses produce case errors. Set `SYMPHONY_EVAL_TOKEN` for optional bearer authentication and use HTTPS for remote endpoints. The client does not follow redirects and uses a 60-second request timeout. Use the embedded API for custom adapters or scorers.

The workspace is a local file archive, not a server or dashboard. Back it up or persist it across CI runs if history should be retained. Limit access and keep it out of source control: suites and run reports may contain sensitive data.

When the CLI encounters a validation or execution error, or a case fails scoring, it creates a separate JSON-lines `evaluation-errors-*.log` under the workspace's `logs` folder and prints the path to stderr. For the default Windows launcher, find it under `.eval-workspace/logs` beside the launcher. A CLI error before a workspace can be identified uses `.eval-logs` in the current directory. Logs identify the suite and case where available. Judge errors include safe diagnostics such as the HTTP status or why a verdict was rejected. The log does not copy credentials, raw exception messages, questions, answers, or provider response bodies.

### Configure the request body for any target

Use `requestBody` in suite `defaults` or on individual cases to match your application's JSON API. The same configuration works with `sse` and `json`. Only the fields in the resolved object are sent; the usual adapter body is used only when no `requestBody` is configured.

Optionally set `SYMPHONY_EVAL_REQUEST_BODY` to a single-line JSON object in the launcher `.env` or process environment. For example:

```dotenv
SYMPHONY_EVAL_REQUEST_BODY={"fromUrl":"https://app.example.com/items/12345"}
```

Top-level fields are merged with precedence **case → suite defaults → environment object**. The launcher uses the dotenv object only if the process variable is unset or blank. Overriding a field replaces its entire value, including nested objects and arrays; an explicit null is sent as JSON null. URLs are sent as data, not fetched. Keep credentials out of request templates; use target authentication headers. `SYMPHONY_EVAL_JUDGE_TOKEN` is for the scoring service only.

```yaml
version: 1
defaults:
  requestBody: {query: '${question}', session: '${sessionId}', key: CHAT, fromUrl: 'https://app.example.com/items/23456'}
cases:
  - {id: item-status, question: 'What is the status of this item?', scoring: contains, expectedAnswer: 'Approved', requestBody: {fromUrl: 'https://app.example.com/items/34567', options: {includeHistory: false, limit: 5}}}
```

Available placeholders are `${question}`, `${sessionId}` (a fresh UUID per case), `${caseId}`, and `${model}`. They are replaced only when they are the entire string value, including inside objects and arrays. Other values are preserved. For a JSON response envelope, select the answer with `answerPointer` (for example, `/message`). For explicit CLI runs, pass the adapter after the endpoint for `run`, or before the endpoint for `run-all`.

### Run all suites against Symphony Demo

`run-all` writes a self-contained HTML report alongside its JSON output (for example, `demo-report.json` produces `demo-report.html`). Open the HTML in a browser to see suite summaries and expandable case details, including expected and actual answers. Neither file is overwritten; choose a fresh report name for each run. Both files can contain sensitive evaluation data.

From this module directory, build with `mvn package`, start Symphony Demo separately, and run:

```text
java -jar target/symphony-eval-1.1.1-SNAPSHOT-cli.jar .eval-demo add demo-inventory examples/demo-inventory.yaml
java -jar target/symphony-eval-1.1.1-SNAPSHOT-cli.jar .eval-demo add demo-knowledge examples/demo-knowledge.yaml
java -jar target/symphony-eval-1.1.1-SNAPSHOT-cli.jar .eval-demo run-all demo-stream http://127.0.0.1:8085/directchat/stream demo-report.json
```

Register each suite once. On subsequent runs, choose a fresh report filename: `run-all` never overwrites an existing report. It runs all suites registered in that workspace against one target, archives each completed suite in its normal history, and writes one JSON report with per-case results and any suite-level errors. Use a dedicated workspace for the demo. The demo is unauthenticated and chooses its own model; `server-default` is a label, not a model override. These read-only cases exercise the live chat API, so model output can vary. `run-all` also accepts the `json` adapter. Exit status is `0` if all cases pass, `1` for case failures or errors, and `2` for invocation or suite-level errors.

The Demo adapter joins `content` events in order and only scores after a clean end of stream. HTTP failures, SSE errors, unfinished frames, missing content, human-input pauses, streams over 8 MiB, and the 60-second deadline produce case errors rather than partial scores. Other APIs can use the JSON adapter if they implement the JSON request and response contract above; a custom auth header does not change that contract.

## Install and configure

From the Semantic Symphony repository root, build and install into the local Maven repository:

```shell
mvn -f symphony-eval/pom.xml install
```

If the IDE only loads the core Symphony project, open this module as a separate Maven project to run its tests there.

For in-process use, add `org.symphonykernel:symphony-eval:1.1.1-SNAPSHOT` to the consuming application's Maven dependencies (no classifier; `test` scope only if used exclusively by tests). If the application manages Jackson versions, keep its Jackson dependencies aligned on a supported 2.x BOM. Copy [the example suite](examples/smoke.yaml) into your application as `evaluations/smoke.yaml` and adapt the questions and model names.

- `version` must be `1`. Each case needs a unique `id`, a `question`, and an `expectedAnswer`. Quote text that YAML might otherwise treat as a number, boolean, or null. Objects, arrays, numbers, booleans, and explicit null are supported.
- The `defaults` section is optional. Case values override suite defaults. If neither specifies `model`, the evaluator sends `server-default` to adapters that forward the application model. If neither specifies `scoring`, a nonblank case `rubric` selects `llm-judge`; otherwise scoring defaults to `exact`. `passThreshold` defaults to `1.0`. LLM judge cases still need a judge model from YAML or `SYMPHONY_EVAL_JUDGE_MODEL`.
- `answerPointer` is an optional [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) into the adapter's answer. An absent field fails the case; an explicit JSON null can be compared.
- `exact` compares case-sensitive text with leading and trailing whitespace removed, or uses Jackson JSON equality for structured values. `contains` checks for a case-sensitive substring and requires a non-blank text expectation.

## Run a suite

The adapter receives the case ID, question, model, and optional resolved `requestBody`, never the expected answer. Create a fresh session per case. In a Symphony application with an injected `KnowledgeGraphBuilder graph`, a one-shot adapter can use the normal non-streaming request path:

```java
import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.TextNode;
import org.symphonykernel.ChatRequest;
import org.symphonykernel.eval.EvaluationRunner;
import org.symphonykernel.eval.YamlEvaluationSuiteLoader;

// Inside an application integration test or CLI:
var runner = new EvaluationRunner(input -> {
    var request = new ChatRequest();
    request.setUser("evaluation");
    request.setSession("eval-" + UUID.randomUUID());
    request.setQuery(input.question());
    request.setPayload(input.model()); // Symphony's model-only payload convention
    var response = graph.process(request);
    if ("FAILED".equalsIgnoreCase(response.getStatusCode())
            || "ERROR".equalsIgnoreCase(response.getStatusCode())
            || "PROCESSING".equalsIgnoreCase(response.getStatusCode())) {
        throw new IllegalStateException("No successful final answer");
    }
    return response.getData() != null ? response.getData() : TextNode.valueOf(response.getMessage());
});

var suite = new YamlEvaluationSuiteLoader().load(Path.of("evaluations/smoke.yaml"));
var report = runner.run(suite);
report.writeJson(Path.of("evaluation-result.json"));
if (!report.successful()) throw new AssertionError("Evaluation failed: " + report.summary());
```

Adapt the response mapping and pointers to your application (Symphony's `getData()` returns an array). For workflows that pause or run asynchronously, the adapter must handle that lifecycle before returning the final answer. Configure request deadlines in the adapter or its client. Run against isolated fixtures or a test environment, especially for workflows that write data or call tools.

To use the same persistent registry and history in your own Java application, create an `EvaluationWorkspace` with a directory path and use `addSuite`, `updateSuite`, `listSuites`, `execute`, `history`, and `getRun`. Pass the same runner (including its scorer registry) when validating and executing suites. No HTTP endpoint or JUnit test is required to use this API.

## Custom scoring and reports

### Optional LLM judge

Set `scoring: llm-judge` explicitly or omit both case and suite scoring and provide a nonblank case `rubric` to select it automatically. An explicit case scorer or suite default takes precedence over the rubric. Deterministic cases never call the judge. If any case uses LLM scoring, configure the judge before running that suite.

`model` still selects the application model where supported; `judgeModel` selects a separate scoring model (or deployment name). You can specify `judgeModel` on each judge case or inherit it from the suite defaults. The judge does not choose which scorer or model to use. See the [mixed Symphony Demo suite](examples/demo-mixed-scoring.yaml) for a deterministic inventory check and an LLM-graded return policy in the same run. Replace its placeholder `judgeModel` with your deployment name before running.

```yaml
version: 1
defaults:
    model: server-default
    judgeModel: approved-judge-deployment
cases:
    - id: report-completeness
        question: Summarize the example report.
        scoring: llm-judge
        passThreshold: 0.9
        expectedAnswer: |
            Market A lists 13 restrictions. Material X is limited to 0.25%.
        rubric: |
            Treat the market count, material identity, concentration, and unit as critical.
            Ignore layout and wording. Mark missing or contradicted critical facts as critical failures.
```

Set the full OpenAI-compatible Chat Completions endpoint (must support JSON mode) via `SYMPHONY_EVAL_JUDGE_URL` or `judge.url` in an explicitly supplied properties file. Copy [the example properties file](examples/eval.properties) and replace the placeholder URL. Put `--config` before the workspace argument, for example:

```text
java -jar <cli-jar> --config examples/eval.properties .eval-workspace run <suite-id> <target-api-url>
```

A nonblank `SYMPHONY_EVAL_JUDGE_URL` overrides the file. The CLI does not auto-load local config or `.env` files. Only `judge.url` is accepted in the file; use `SYMPHONY_EVAL_JUDGE_TOKEN` for judge bearer authentication and `SYMPHONY_EVAL_TOKEN` for target authentication. Do not embed secrets in URLs. The target API URL still goes on the command line.

Suite registration works offline; execution requires judge configuration only for judge cases. A critical failure produces a score of zero regardless of the model's proposed score; malformed, rejected, or incomplete verdicts are case errors. The report includes the configured judge model, score, and explanation. Java callers can register `LlmJudgeScorer` under `LlmJudgeScorer.NAME`.

To share a judge deployment across suites, set `SYMPHONY_EVAL_JUDGE_MODEL` in the `.env` beside the Windows launcher. Leave `judgeModel` out of YAML to use it. The first nonblank value wins: case `judgeModel`, suite `defaults.judgeModel`, process environment, then launcher `.env`. For a direct CLI JAR invocation, set the process environment variable instead. This fallback applies to both suite registration and execution and does not change the application `model`.

Use `exact` with `answerPointer` for structured numbers, IDs, and units when available. Use the judge for semantic completeness and qualifications; for large reports, prefer smaller sections or explicit checklists where possible. Scores can vary and do not certify regulatory correctness. Pin an approved judge deployment, calibrate it with known omissions and contradictions, and use only endpoints approved to receive the full question, reference, rubric, and actual answer.

### Custom Java scorers

Register custom scorers via `new EvaluationRunner(provider, Map.of("rubric", scorer))`. A scorer receives the expected and actual answers, the original question and model, and the optional YAML `judgeModel` and `rubric`. It returns an `EvaluationRunner.Score` in `[0, 1]` and a short explanation. The runner itself is model-agnostic.

The runner validates configuration before invoking the application, then runs cases in order. Adapter and scorer failures produce `ERROR` without stopping later cases; thread interruption cancels the run. The report includes the selected actual answer, expected answer, configured model, score, outcome, and duration. Pass rate is passes divided by all cases; mean score excludes errors and is null if nothing was scored. Reports may contain sensitive inputs or answers, so restrict where they are stored or published.