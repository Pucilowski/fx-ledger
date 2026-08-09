package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * At-least-once delivery: with the publisher dead (never started), the
 * reconciler's periodic sweep still gets every outbox event onto the stream.
 */
@Testcontainers
class ReconcilerTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static App app;
    static HttpClient client;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        var dataSource = Database.connect(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        app = new App(dataSource, 0,
                new EventPublisher.Config(false, Duration.ofMillis(200)));
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        app.stop();
        client.close();
    }

    @Test
    void reconcilerAlonePublishesEveryEvent() throws Exception {
        var account = post("/accounts", """
                {"ownerId": "%s", "currency": "GBP"}""".formatted(UUID.randomUUID()));
        var accountId = json.readTree(account.body()).get("id").asText();
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "100.00"}""");

        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            var response = get("/events?after=0&limit=100");
            if (response.body().contains("TransactionCompletedEvent")) {
                return; // delivered without any publisher
            }
            Thread.sleep(100);
        }
        throw new AssertionError("reconciler never published the deposit's events");
    }

    // -- helpers --

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
