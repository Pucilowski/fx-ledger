package com.pucilowski.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** fx-service's view of ledger-service: the event feed and the hedge action. */
public final class LedgerClient {

    public record LedgerEvent(long offset, String eventType, JsonNode payload) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public LedgerClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<LedgerEvent> events(long after, int limit) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/events?after=" + after + "&limit=" + limit))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ledger /events returned " + response.statusCode());
        }
        var events = new ArrayList<LedgerEvent>();
        for (var node : JSON.readTree(response.body()).get("events")) {
            events.add(new LedgerEvent(
                    node.get("offset").asLong(),
                    node.get("eventType").asText(),
                    node.get("payload")));
        }
        return events;
    }

    /**
     * Records a filled hedge on the ledger. 201 and 409 both mean the hedge
     * is settled — 409 is the idempotent replay answer.
     */
    public void settleHedge(String hedgeId, String providerId,
                            String fromCurrency, BigDecimal fromAmount,
                            String toCurrency, BigDecimal toAmount) throws Exception {
        var body = JSON.writeValueAsString(Map.of(
                "hedgeId", hedgeId,
                "providerId", providerId,
                "fromCurrency", fromCurrency,
                "fromAmount", fromAmount.toPlainString(),
                "toCurrency", toCurrency,
                "toAmount", toAmount.toPlainString()));
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/hedge-settlements"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("hedge settlement returned " + response.statusCode()
                    + ": " + response.body());
        }
    }
}
