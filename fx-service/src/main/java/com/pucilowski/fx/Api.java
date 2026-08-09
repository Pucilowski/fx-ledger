package com.pucilowski.fx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import spark.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class Api {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Quotes quotes;
    private final Positions positions;
    private final Hedger hedger;

    Api(Quotes quotes, Positions positions, Hedger hedger) {
        this.quotes = quotes;
        this.positions = positions;
        this.hedger = hedger;
    }

    void routes(Service http) {
        http.post("/quotes", (request, response) -> {
            var body = parse(request.body());
            var quote = quotes.create(
                    required(body, "fromCurrency"),
                    required(body, "toCurrency"),
                    amount(body));
            response.status(201);
            response.type("application/json");
            return write(quoteJson(quote));
        });

        // Observability: the current house position projection and how far
        // into the ledger stream it has read.
        http.get("/positions", (request, response) -> {
            response.type("application/json");
            var byCurrency = new TreeMap<String, String>();
            positions.snapshot().forEach((currency, amount) ->
                    byCurrency.put(currency, amount.toPlainString()));
            return write(Map.of(
                    "positions", byCurrency,
                    "offset", hedger == null ? 0 : hedger.offset()));
        });

        http.exception(IllegalArgumentException.class, (e, request, response) -> {
            response.status(400);
            response.type("application/json");
            response.body(errorJson(e.getMessage()));
        });
        http.exception(NoLiquidityException.class, (e, request, response) -> {
            response.status(503);
            response.type("application/json");
            response.body(errorJson(e.getMessage()));
        });
    }

    private static Map<String, Object> quoteJson(Quotes.Quote quote) {
        var json = new LinkedHashMap<String, Object>();
        json.put("id", quote.id());
        json.put("fromCurrency", quote.fromCurrency());
        json.put("toCurrency", quote.toCurrency());
        json.put("fromAmount", quote.fromAmount());
        json.put("toAmount", quote.toAmount());
        json.put("rate", quote.rate());
        json.put("expiresAt", quote.expiresAt());
        json.put("signature", quote.signature());
        return json;
    }

    private static JsonNode parse(String body) {
        try {
            var node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("expected a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed JSON");
        }
    }

    private static String required(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return value.asText();
    }

    private static BigDecimal amount(JsonNode body) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(required(body, "fromAmount"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("fromAmount must be a decimal number");
        }
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw new IllegalArgumentException("fromAmount must be positive with at most four decimal places");
        }
        return amount;
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String errorJson(String message) {
        return write(Map.of("error", message == null ? "bad request" : message));
    }
}
