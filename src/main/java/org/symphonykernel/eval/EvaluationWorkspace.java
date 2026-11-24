package org.symphonykernel.eval;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Local suite registry and run archive. Use a separate root per application/environment. */
public final class EvaluationWorkspace {
    private static final Pattern SUITE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private final Path root;
    private final YamlEvaluationSuiteLoader loader = new YamlEvaluationSuiteLoader();
    private final ObjectMapper json = new ObjectMapper();

    public EvaluationWorkspace(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Registers a new suite without overwriting an existing ID. */
    public void addSuite(String id, Path source, EvaluationRunner runner) throws IOException {
        Path target = suitePath(id);
        byte[] yaml = validatedYaml(source, runner);
        Files.createDirectories(target.getParent());
        Files.write(target, yaml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Replaces a registered suite without deleting its past runs. */
    public void updateSuite(String id, Path source, EvaluationRunner runner) throws IOException {
        Path target = suitePath(id);
        if (!Files.isRegularFile(target)) {
            throw new java.nio.file.NoSuchFileException(target.toString());
        }
        publish(target, validatedYaml(source, runner), true);
    }

    public List<String> listSuites() throws IOException {
        return files(root.resolve("suites"), "suite-*.yaml").stream()
                .map(path -> path.getFileName().toString())
                .map(name -> name.substring(6, name.length() - 5))
                .filter(id -> SUITE_ID.matcher(id).matches()).sorted().toList();
    }

    /** Archives completed runs, including runs with failed cases or case errors. */
    public Run execute(String id, EvaluationRunner runner) throws IOException, InterruptedException {
        byte[] yaml = Files.readAllBytes(suitePath(id));
        EvaluationSuite suite = loader.load(yaml);
        Instant started = Instant.now();
        EvaluationReport report = runner.run(suite);
        UUID runId = UUID.randomUUID();
        Run run = new Run(new RunSummary(runId, id, fingerprint(yaml), started.toString(),
                Instant.now().toString(), report.summary()), report);
        Path target = runPath(id, runId);
        Files.createDirectories(target.getParent());
        publish(target, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(run), false);
        return run;
    }

    /** Returns one suite's run summaries, newest first. */
    public List<RunSummary> history(String id) throws IOException {
        List<RunSummary> summaries = new ArrayList<>();
        for (Path path : files(historyPath(id), "*.json")) {
            // Read only the metadata field into Java objects, skipping potentially large case arrays.
            try (var parser = json.createParser(path.toFile())) {
                if (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                    while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                        String name = parser.currentName();
                        parser.nextToken();
                        if ("metadata".equals(name)) {
                            summaries.add(json.readValue(parser, RunSummary.class));
                            break;
                        }
                        parser.skipChildren();
                    }
                }
            }
        }
        summaries.sort(Comparator.comparing((RunSummary run) -> Instant.parse(run.startedAt())).reversed()
                .thenComparing(RunSummary::runId));
        return List.copyOf(summaries);
    }

    public Run getRun(String id, UUID runId) throws IOException {
        return json.readValue(runPath(id, runId).toFile(), Run.class);
    }

    private byte[] validatedYaml(Path source, EvaluationRunner runner) throws IOException {
        byte[] yaml = Files.readAllBytes(source);
        runner.validate(loader.load(yaml));
        return yaml;
    }

    private Path suitePath(String id) {
        return root.resolve("suites").resolve("suite-" + checkedId(id) + ".yaml");
    }

    private Path historyPath(String id) {
        return root.resolve("runs").resolve("suite-" + checkedId(id));
    }

    private Path runPath(String id, UUID runId) {
        return historyPath(id).resolve(Objects.requireNonNull(runId, "runId") + ".json");
    }

    private static String checkedId(String id) {
        if (id == null || !SUITE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Suite ID must match " + SUITE_ID.pattern());
        }
        return id;
    }

    private static List<Path> files(Path directory, String glob) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory, glob)) {
            for (Path path : entries) {
                if (Files.isRegularFile(path)) {
                    paths.add(path);
                }
            }
        } catch (java.nio.file.NoSuchFileException absent) {
            return List.of();
        }
        return paths;
    }

    private static void publish(Path target, byte[] contents, boolean replace) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            Files.write(temp, contents);
            try {
                // New runs have generated UUID destinations; updates intentionally replace their destination.
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (replace) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temp, target);
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String fingerprint(byte[] yaml) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record RunSummary(UUID runId, String suiteId, String suiteSha256, String startedAt, String finishedAt,
                             EvaluationReport.Summary summary) { }

    public record Run(RunSummary metadata, EvaluationReport report) { }
}