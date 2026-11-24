package org.symphonykernel.eval;

import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/** Renders a local, script-free view of a batch report. */
final class HtmlEvaluationReport {
    private HtmlEvaluationReport() { }

    static Path companionPath(Path json) {
        String name = json.getFileName().toString();
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return json.resolveSibling(stem + ".html");
    }

    static String render(EvaluationCli.BatchReport report) {
        var page = new StringBuilder("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
                <title>Symphony Evaluation Report</title>
                <style>
                body{font:1rem system-ui,sans-serif;color:#172339;background:#f5f7fa;margin:0}
                main{max-width:65rem;margin:auto;padding:1.5rem}
                section{background:white;border:1px solid #d6deea;border-radius:12px;padding:1rem 1.25rem;margin:1rem 0}
                h1,h2{margin:.25rem 0 .6rem}details{border-top:1px solid #d6deea;padding:.75rem 0}
                summary{cursor:pointer}.badge{font-weight:700;padding:.15rem .5rem;border-radius:6px;background:#fce5df;color:#8c2417}
                .pass{background:#d9f4e5;color:#11623b}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f5f9;padding:.75rem;border-radius:6px}
                </style></head><body><main><header><h1>Symphony Evaluation Report</h1>
                """);
        page.append("<p><strong>Overall:</strong> ").append(report.successful() ? "Passed" : "Not passed")
                .append(" &middot; ").append(escape(report.adapter())).append("</p><p>")
                .append(escape(report.startedAt())).append(" &rarr; ").append(escape(report.finishedAt()))
                .append("</p></header>");
        if (!report.suiteErrors().isEmpty()) {
            page.append("<section><h2>Suite errors</h2><ul>");
            for (var error : report.suiteErrors()) {
                page.append("<li>").append(escape(error.suiteId())).append(": ")
                        .append(escape(error.errorType())).append("</li>");
            }
            page.append("</ul></section>");
        }
        for (var run : report.runs()) {
            var summary = run.report().summary();
            page.append("<section><h2>").append(escape(run.metadata().suiteId())).append("</h2><p>")
                    .append(String.format(Locale.ROOT, "%.1f%% pass &middot; %d passed / %d failed / %d errors",
                            100 * summary.passRate(), summary.passed(), summary.failed(), summary.errors()))
                    .append("</p>");
            for (var result : run.report().cases()) {
                appendCase(page, result);
            }
            page.append("</section>");
        }
        return page.append("</main></body></html>\n").toString();
    }

    private static void appendCase(StringBuilder page, EvaluationReport.Result result) {
        boolean pass = result.status() == EvaluationReport.Status.PASS;
        page.append(pass ? "<details>" : "<details open>").append("<summary><span class=\"badge")
                .append(pass ? " pass" : "").append("\">").append(result.status()).append("</span> ")
                .append(escape(result.id())).append("</summary><p><strong>Scoring:</strong> ")
                .append(escape(result.scoring()));
        if (result.judgeModel() != null) {
            page.append(" &middot; Judge: ").append(escape(result.judgeModel()));
        }
        page.append(" &middot; Score: ").append(result.score() == null ? "not scored" : result.score())
                .append(" &middot; Threshold: ").append(result.passThreshold()).append("</p>");
        block(page, "Question", result.question());
        block(page, "Expected answer", answer(result.expectedAnswer()));
        block(page, "Actual answer", answer(result.actualAnswer()));
        block(page, "Scoring details", result.detail());
        page.append("</details>");
    }

    private static String answer(JsonNode value) {
        if (value == null) {
            return "Not available";
        }
        return value.isTextual() ? value.textValue() : value.toPrettyString();
    }

    private static void block(StringBuilder page, String label, String text) {
        page.append("<h3>").append(label).append("</h3><pre>").append(escape(text)).append("</pre>");
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}