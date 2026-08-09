package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: the event stream. Model events are written in the same transaction
 * as the journal (transactional outbox), published with dense stream offsets,
 * and served as a catch-up feed (GET /events?after=N) plus a live SSE stream
 * (GET /events/stream).
 */
@Testcontainers
class EventsTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static App app;
    static HttpClient client;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        var dataSource = Database.connect(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        app = new App(dataSource, 0);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        app.stop();
        client.close();
    }

    @Test
    void depositEmitsBalanceChangedAndTransactionCompleted() throws Exception {
        var before = latestOffset();
        var accountId = newAccount("GBP");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "100.00"}""");

        var events = pollUntil(before, "TransactionCompletedEvent");

        var balanceChanged = events.stream()
                .filter(e -> e.get("eventType").asText().equals("BalanceChangedEvent"))
                .filter(e -> e.get("payload").get("accountId").asText().equals(accountId))
                .findFirst().orElseThrow();
        assertThat(balanceChanged.get("modelType").asText()).isEqualTo("account");
        assertThat(new BigDecimal(balanceChanged.get("payload").get("balance").asText()))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(new BigDecimal(balanceChanged.get("payload").get("delta").asText()))
                .isEqualByComparingTo(new BigDecimal("100.00"));

        var completed = events.stream()
                .filter(e -> e.get("eventType").asText().equals("TransactionCompletedEvent"))
                .findFirst().orElseThrow();
        assertThat(completed.get("payload").get("type").asText()).isEqualTo("deposit");
        assertThat(completed.get("payload").get("entries")).hasSize(2);
    }

    @Test
    void offsetsAreDenseAndOrdered() throws Exception {
        var accountId = newAccount("EUR");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "10.00"}""");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "20.00"}""");
        pollUntil(0, "TransactionCompletedEvent");

        var offsets = events(0, 1000).stream()
                .map(e -> e.get("offset").asLong())
                .toList();

        assertThat(offsets).isNotEmpty();
        for (int i = 0; i < offsets.size(); i++) {
            assertThat(offsets.get(i)).isEqualTo(i + 1); // dense: 1, 2, 3, ...
        }
    }

    @Test
    void catchUpFeedPagesByOffset() throws Exception {
        var accountId = newAccount("USD");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "10.00"}""");
        var seen = pollUntil(0, "TransactionCompletedEvent");
        var mid = seen.get(seen.size() - 1).get("offset").asLong();

        post("/accounts/" + accountId + "/deposits", """
                {"amount": "20.00"}""");
        var later = pollUntil(mid, "TransactionCompletedEvent");

        assertThat(later).allSatisfy(event ->
                assertThat(event.get("offset").asLong()).isGreaterThan(mid));
    }

    @Test
    void rejectedOperationEmitsNothing() throws Exception {
        var accountId = newAccount("GBP");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "10.00"}""");
        pollUntil(0, "TransactionCompletedEvent");
        var before = latestOffset();

        var response = post("/accounts/" + accountId + "/withdrawals", """
                {"amount": "999.00"}""");
        assertThat(response.statusCode()).isEqualTo(422);

        Thread.sleep(700); // give the publisher every chance to prove us wrong
        assertThat(events(before, 100)).isEmpty();
    }

    @Test
    void liveStreamDeliversEventsOverSse() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/events/stream"))
                .build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var body = response.body();
             var reader = new BufferedReader(new InputStreamReader(body))) {
            var sawTransaction = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ") && line.contains("TransactionCompletedEvent")) {
                        return true;
                    }
                }
                return false;
            });

            var accountId = newAccount("GBP");
            post("/accounts/" + accountId + "/deposits", """
                    {"amount": "42.00"}""");

            assertThat(sawTransaction.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // -- helpers --

    static long latestOffset() throws Exception {
        return events(0, 10_000).stream()
                .mapToLong(e -> e.get("offset").asLong())
                .max().orElse(0);
    }

    static List<JsonNode> events(long after, int limit) throws Exception {
        var response = get("/events?after=" + after + "&limit=" + limit);
        assertThat(response.statusCode()).isEqualTo(200);
        var events = new ArrayList<JsonNode>();
        json.readTree(response.body()).get("events").forEach(events::add);
        return events;
    }

    /** Polls the catch-up feed until an event of the given type shows up past the offset. */
    static List<JsonNode> pollUntil(long after, String eventType) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            var events = events(after, 1000);
            if (events.stream().anyMatch(e -> e.get("eventType").asText().equals(eventType))) {
                return events;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no %s appeared after offset %d".formatted(eventType, after));
    }

    static String newAccount(String currency) throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(UUID.randomUUID(), currency));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
