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
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger's core invariant: money is conserved. Every journal balances to
 * zero per currency, so the sum of ALL entries ever written is zero per
 * currency — regardless of what sequence of operations ran, including
 * operations that were rejected.
 */
@Testcontainers
class ConservationTest {

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
    void moneyIsConservedAcrossAnyMixOfOperations() throws Exception {
        for (var currency : new String[]{"GBP", "EUR", "USD"}) {
            for (int i = 0; i < 3; i++) {
                var accountId = newAccount(currency);
                post("/accounts/" + accountId + "/deposits", amount("100.00"));
                post("/accounts/" + accountId + "/withdrawals", amount("30.50"));
                post("/accounts/" + accountId + "/withdrawals", amount("999.99")); // rejected: insufficient
                post("/accounts/" + accountId + "/deposits", amount("-1.00"));     // rejected: invalid
                post("/accounts/" + accountId + "/deposits", amount("0.01"));
            }
        }

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            // Global conservation: entries sum to zero per currency.
            try (var statement = connection.createStatement()) {
                var perCurrency = statement.executeQuery("""
                        select currency, sum(amount) as total
                        from journal_entry
                        group by currency
                        """);
                var currenciesSeen = 0;
                while (perCurrency.next()) {
                    currenciesSeen++;
                    assertThat(perCurrency.getBigDecimal("total"))
                            .as("net movement in %s", perCurrency.getString("currency"))
                            .isEqualByComparingTo(BigDecimal.ZERO);
                }
                assertThat(currenciesSeen).isEqualTo(3);
            }

            // Local conservation: every individual journal balances per currency.
            try (var statement = connection.createStatement()) {
                var unbalanced = statement.executeQuery("""
                        select journal_id
                        from journal_entry
                        group by journal_id, currency
                        having sum(amount) <> 0
                        """);
                assertThat(unbalanced.next()).as("unbalanced journals exist").isFalse();
            }

            // Cached balances agree with the journal: for every account, the
            // stored balance equals the sum of its entries.
            try (var statement = connection.createStatement()) {
                var drifted = statement.executeQuery("""
                        select a.id
                        from account a
                        left join journal_entry e on e.account_id = a.id
                        group by a.id, a.balance
                        having a.balance <> coalesce(sum(e.amount), 0)
                        """);
                assertThat(drifted.next()).as("accounts whose balance drifted from the journal").isFalse();
            }
        }
    }

    // -- helpers --

    static String amount(String value) {
        return "{\"amount\": \"%s\"}".formatted(value);
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
}
