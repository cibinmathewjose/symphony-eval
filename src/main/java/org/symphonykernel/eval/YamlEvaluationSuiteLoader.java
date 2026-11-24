package org.symphonykernel.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/** Loads one YAML document. The runner validates case settings before calling the application. */
public final class YamlEvaluationSuiteLoader {
    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    public EvaluationSuite load(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return requireSuite(mapper.readValue(input, EvaluationSuite.class));
        }
    }

    EvaluationSuite load(byte[] yaml) throws IOException {
        return requireSuite(mapper.readValue(yaml, EvaluationSuite.class));
    }

    private static EvaluationSuite requireSuite(EvaluationSuite suite) throws IOException {
        if (suite == null) {
            throw new IOException("Expected a YAML evaluation suite");
        }
        return suite;
    }
}