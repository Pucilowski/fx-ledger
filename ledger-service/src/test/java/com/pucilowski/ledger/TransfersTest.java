package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2: transfers between accounts.
 *
 *   POST /transfers {"fromAccountId", "toAccountId", "amount"} -> 201 sender account JSON
 * Same-currency only (400 otherwise); insufficient funds -> 422 leaving both
 * balances untouched; unknown account -> 404.
 */
@Testcontainers
class TransfersTest {

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
    void transferMovesMoneyBetweenAccounts() throws Exception {
        var from = newAccount("GBP");
        var to = newAccount("GBP");
        post("/accounts/" + from + "/deposits", """
                {"amount": "100.00"}""");

        var response = post("/transfers", transfer(from, to, "40.00"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(balance(from)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(balance(to)).isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    void transferBeyondBalanceIs422AndTouchesNeitherBalance() throws Exception {
        var from = newAccount("GBP");
        var to = newAccount("GBP");
        post("/accounts/" + from + "/deposits", """
                {"amount": "10.00"}""");

        var response = post("/transfers", transfer(from, to, "50.00"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(balance(from)).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(balance(to)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void transferAcrossCurrenciesIs400() throws Exception {
        var from = newAccount("GBP");
        var to = newAccount("EUR");
        post("/accounts/" + from + "/deposits", """
                {"amount": "10.00"}""");

        assertThat(post("/transfers", transfer(from, to, "5.00")).statusCode()).isEqualTo(400);
    }

    @Test
    void transferToUnknownAccountIs404() throws Exception {
        var from = newAccount("GBP");
        post("/accounts/" + from + "/deposits", """
                {"amount": "10.00"}""");

        assertThat(post("/transfers", transfer(from, UUID.randomUUID().toString(), "5.00"))
                .statusCode()).isEqualTo(404);
    }

    @Test
    void transferToSelfIs400() throws Exception {
        var account = newAccount("GBP");
        post("/accounts/" + account + "/deposits", """
                {"amount": "10.00"}""");

        assertThat(post("/transfers", transfer(account, account, "5.00")).statusCode()).isEqualTo(400);
    }

    // -- helpers --

    static String transfer(String from, String to, String amount) {
        return """
                {"fromAccountId": "%s", "toAccountId": "%s", "amount": "%s"}"""
                .formatted(from, to, amount);
    }

    static String newAccount(String currency) throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(UUID.randomUUID(), currency));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    static BigDecimal balance(String accountId) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/accounts/" + accountId))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new BigDecimal(json.readTree(response.body()).get("balance").asText());
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
