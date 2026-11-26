package org.symphonykernel.eval;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Resolves application-defined, typed JSON request fields without interpreting their names. */
final class RequestBodyTemplate {
    private RequestBodyTemplate() { }

    static ObjectNode resolve(String id, String question, String model, ObjectNode... layers) {
        ObjectNode merged = null;
        for (ObjectNode layer : layers) {
            if (layer != null) {
                if (merged == null) {
                    merged = JsonNodeFactory.instance.objectNode();
                }
                merged.setAll(layer);
            }
        }
        if (merged == null) {
            return null;
        }
        var variables = Map.of("${caseId}", id, "${question}", question, "${model}", model,
                "${sessionId}", UUID.randomUUID().toString());
        return (ObjectNode) substitute(merged, variables);
    }

    private static JsonNode substitute(JsonNode node, Map<String, String> variables) {
        if (node.isTextual()) {
            return TextNode.valueOf(variables.getOrDefault(node.textValue(), node.textValue()));
        }
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(field -> result.set(field.getKey(), substitute(field.getValue(), variables)));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(substitute(value, variables)));
            return result;
        }
        return node;
    }
}