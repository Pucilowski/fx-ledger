package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: conversions. A signed quote is the instruction; the ledger
 * verifies it locally (signature, expiry), spends it exactly once
 * (idempotency key on the journal), and executes the four-entry conversion
 * journal — customer from-currency to house, house to-currency to customer.
 * The default dev secret signs the test quotes, matching the app's default.
 */
@Testcontainers
class ConversionsTest {

    static final String SECRET = "dev-secret";

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
    void conversionMovesBothCurrenciesAtTheQuotedAmounts() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "1000.00"}""");

        var response = post("/convert", convertBody(
                quoteJson("GBP", "EUR", "800.00", "918.1600", futureExpiry()), gbp, eur));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(balance(gbp)).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(balance(eur)).isEqualByComparingTo(new BigDecimal("918.16"));
    }

    @Test
    void houseTakesTheOtherSideOfTheTrade() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "500.00"}""");

        var houseGbpBefore = houseBalance("GBP");
        var houseEurBefore = houseBalance("EUR");
        var response = post("/convert", convertBody(
                quoteJson("GBP", "EUR", "400.00", "459.0800", futureExpiry()), gbp, eur));
        assertThat(response.statusCode()).isEqualTo(201);

        assertThat(houseBalance("GBP").subtract(houseGbpBefore))
                .isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(houseBalance("EUR").subtract(houseEurBefore))
                .isEqualByComparingTo(new BigDecimal("-459.08")); // the house went short EUR
    }

    @Test
    void reusedQuoteIs409AndMovesNothingTwice() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "1000.00"}""");
        var quote = quoteJson("GBP", "EUR", "100.00", "114.7700", futureExpiry());

        assertThat(post("/convert", convertBody(quote, gbp, eur)).statusCode()).isEqualTo(201);
        assertThat(post("/convert", convertBody(quote, gbp, eur)).statusCode()).isEqualTo(409);

        assertThat(balance(gbp)).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(balance(eur)).isEqualByComparingTo(new BigDecimal("114.77"));
    }

    @Test
    void expiredQuoteIs422() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "100.00"}""");
        var expired = Instant.now().minusSeconds(5).toString();

        var response = post("/convert", convertBody(
                quoteJson("GBP", "EUR", "50.00", "57.3800", expired), gbp, eur));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(balance(gbp)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void tamperedQuoteIs400() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "100.00"}""");

        // Sign for 57.38 EUR, then claim 5738.00 EUR.
        var honest = quoteJson("GBP", "EUR", "50.00", "57.3800", futureExpiry());
        var tampered = honest.replace("\"toAmount\": \"57.3800\"", "\"toAmount\": \"5738.0000\"");

        var response = post("/convert", convertBody(tampered, gbp, eur));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(balance(gbp)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void accountsOfDifferentOwnersAre400() throws Exception {
        var gbp = newAccount(UUID.randomUUID(), "GBP");
        var eur = newAccount(UUID.randomUUID(), "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "100.00"}""");

        var response = post("/convert", convertBody(
                quoteJson("GBP", "EUR", "50.00", "57.3800", futureExpiry()), gbp, eur));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void conversionsConserveMoneyPerCurrency() throws Exception {
        var owner = UUID.randomUUID();
        var gbp = newAccount(owner, "GBP");
        var eur = newAccount(owner, "EUR");
        post("/accounts/" + gbp + "/deposits", """
                {"amount": "1000.00"}""");
        post("/convert", convertBody(
                quoteJson("GBP", "EUR", "250.00", "286.9200", futureExpiry()), gbp, eur));

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            var perCurrency = statement.executeQuery("""
                    select currency, sum(amount) as total
                    from journal_entry
                    group by currency
                    """);
            while (perCurrency.next()) {
                assertThat(perCurrency.getBigDecimal("total"))
                        .as("net movement in %s", perCurrency.getString("currency"))
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    // -- helpers --

    static String futureExpiry() {
        return Instant.now().plusSeconds(30).toString();
    }

    /** Builds and signs a quote exactly the way fx-service does. */
    static String quoteJson(String from, String to, String fromAmount, String toAmount, String expiresAt) {
        var id = UUID.randomUUID().toString();
        var canonical = String.join("|", id, from, to, fromAmount, toAmount, expiresAt);
        return """
                {"id": "%s", "fromCurrency": "%s", "toCurrency": "%s", "fromAmount": "%s", \
                "toAmount": "%s", "rate": "n/a", "expiresAt": "%s", "signature": "%s"}"""
                .formatted(id, from, to, fromAmount, toAmount, expiresAt, hmac(canonical));
    }

    static String convertBody(String quoteJson, String fromAccountId, String toAccountId) {
        return """
                {"quote": %s, "fromAccountId": "%s", "toAccountId": "%s"}"""
                .formatted(quoteJson, fromAccountId, toAccountId);
    }

    static String hmac(String canonical) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static BigDecimal houseBalance(String currency) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select balance from account
                     where kind = 'house' and currency = ?
                     """)) {
            statement.setString(1, currency);
            var result = statement.executeQuery();
            return result.next() ? result.getBigDecimal("balance") : BigDecimal.ZERO;
        }
    }

    static String newAccount(UUID owner, String currency) throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(owner, currency));
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
