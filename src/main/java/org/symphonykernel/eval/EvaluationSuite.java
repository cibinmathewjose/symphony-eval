package org.symphonykernel.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Versioned suite configuration. Case-level settings override suite defaults. */
public record EvaluationSuite(int version, Defaults defaults, List<Case> cases) {
    public EvaluationSuite {
        if (cases != null) {
            cases = Collections.unmodifiableList(new ArrayList<>(cases));
        }
    }

    public record Defaults(String model, String scoring, Double passThreshold, String judgeModel, ObjectNode requestBody) {
        public Defaults(String model, String scoring, Double passThreshold, String judgeModel) {
            this(model, scoring, passThreshold, judgeModel, null);
        }
    }

    public record Case(String id, String question, @JsonProperty(required = true) JsonNode expectedAnswer,
                       String model, String scoring, Double passThreshold, String answerPointer,
                       String judgeModel, String rubric, ObjectNode requestBody) {
        public Case(String id, String question, JsonNode expectedAnswer, String model, String scoring,
                    Double passThreshold, String answerPointer, String judgeModel, String rubric) {
            this(id, question, expectedAnswer, model, scoring, passThreshold, answerPointer, judgeModel, rubric, null);
        }
    }
}