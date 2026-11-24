package org.symphonykernel.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Versioned suite configuration. Case-level settings override suite defaults. */
public record EvaluationSuite(int version, Defaults defaults, List<Case> cases) {
    public EvaluationSuite {
        if (cases != null) {
            cases = Collections.unmodifiableList(new ArrayList<>(cases));
        }
    }

    public record Defaults(String model, String scoring, Double passThreshold, String judgeModel) { }

    public record Case(String id, String question, @JsonProperty(required = true) JsonNode expectedAnswer,
                       String model, String scoring, Double passThreshold, String answerPointer,
                       String judgeModel, String rubric) { }
}