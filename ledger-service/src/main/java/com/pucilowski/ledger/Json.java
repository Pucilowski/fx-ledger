package com.pucilowski.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode parse(String body) {
        try {
            var node = MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ValidationException("expected a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new ValidationException("malformed JSON");
        }
    }

    static String required(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw new ValidationException("missing field: " + field);
        }
        return value.asText();
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String error(String message) {
        return write(Map.of("error", message));
    }

    private Json() {
    }
}
