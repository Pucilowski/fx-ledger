package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1: accounts, deposits, withdrawals.
 *
 * API under test:
 *   POST /accounts {"ownerId": "<uuid>", "currency": "GBP"}   -> 201 account JSON
 *   GET  /accounts/{id}                                       -> 200 account JSON | 404
 *   POST /accounts/{id}/deposits    {"amount": "100.00"}      -> 201 account JSON
 *   POST /accounts/{id}/withdrawals {"amount": "40.00"}       -> 201 account JSON
 *                                                             |  422 insufficient funds
 * Account JSON: {"id", "ownerId", "currency", "balance"} — balance a decimal string.
 * Malformed input (bad currency, non-positive amount) -> 400.
 *
 * Every deposit/withdrawal writes a journal balancing to zero: the customer
 * entry is matched by an opposite entry on the 'external' world account.
 */
@Testcontainers
class AccountsTest {

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
    void createdAccountStartsAtZeroBalance() throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "GBP"}""".formatted(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(201);
        var account = json.readTree(response.body());
        assertThat(account.get("id").asText()).isNotEmpty();
        assertThat(account.get("currency").asText()).isEqualTo("GBP");
        assertThat(balanceOf(account)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void accountIsRetrievableAfterCreation() throws Exception {
        var created = json.readTree(post("/accounts", """
                {"ownerId": "%s", "currency": "EUR"}""".formatted(UUID.randomUUID())).body());

        var response = get("/accounts/" + created.get("id").asText());

        assertThat(response.statusCode()).isEqualTo(200);
        var account = json.readTree(response.body());
        assertThat(account.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(account.get("currency").asText()).isEqualTo("EUR");
    }

    @Test
    void unknownAccountIs404() throws Exception {
        assertThat(get("/accounts/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void secondAccountInSameCurrencyIs409() throws Exception {
        var owner = UUID.randomUUID();
        var body = """
                {"ownerId": "%s", "currency": "GBP"}""".formatted(owner);

        assertThat(post("/accounts", body).statusCode()).isEqualTo(201);
        assertThat(post("/accounts", body).statusCode()).isEqualTo(409);
    }

    @Test
    void invalidCurrencyIs400() throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "POUNDS"}""".formatted(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void depositIncreasesBalance() throws Exception {
        var accountId = newAccount("GBP");

        var response = post("/accounts/" + accountId + "/deposits", """
                {"amount": "100.00"}""");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(balanceOf(json.readTree(response.body())))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(fetchedBalance(accountId)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void depositWritesABalancedJournal() throws Exception {
        var accountId = newAccount("GBP");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "25.00"}""");

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select sum(e.amount) as total, count(*) as entries
                     from journal_entry e
                     where e.journal_id in
                         (select journal_id from journal_entry where account_id = ?::uuid)
                     """)) {
            statement.setString(1, accountId);
            var result = statement.executeQuery();
            assertThat(result.next()).isTrue();
            assertThat(result.getBigDecimal("total")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getInt("entries")).isEqualTo(2);
        }
    }

    @Test
    void withdrawalDecreasesBalance() throws Exception {
        var accountId = newAccount("GBP");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "100.00"}""");

        var response = post("/accounts/" + accountId + "/withdrawals", """
                {"amount": "40.00"}""");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(fetchedBalance(accountId)).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void withdrawalBeyondBalanceIs422AndLeavesBalanceUntouched() throws Exception {
        var accountId = newAccount("GBP");
        post("/accounts/" + accountId + "/deposits", """
                {"amount": "10.00"}""");

        var response = post("/accounts/" + accountId + "/withdrawals", """
                {"amount": "50.00"}""");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(fetchedBalance(accountId)).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void nonPositiveAmountsAre400() throws Exception {
        var accountId = newAccount("GBP");

        assertThat(post("/accounts/" + accountId + "/deposits", """
                {"amount": "-5.00"}""").statusCode()).isEqualTo(400);
        assertThat(post("/accounts/" + accountId + "/deposits", """
                {"amount": "0"}""").statusCode()).isEqualTo(400);
    }

    // -- helpers --

    static String newAccount(String currency) throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(UUID.randomUUID(), currency));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    static BigDecimal fetchedBalance(String accountId) throws Exception {
        return balanceOf(json.readTree(get("/accounts/" + accountId).body()));
    }

    static BigDecimal balanceOf(JsonNode account) {
        return new BigDecimal(account.get("balance").asText());
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
