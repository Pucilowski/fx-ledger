package com.pucilowski.fx;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6, end to end across both real services and a real database:
 *
 *   quote (fx) -> convert (ledger) -> events stream out -> fx's position
 *   projection sees the house go short -> threshold breached -> hedge fired
 *   -> settlement journal on the ledger -> events flow back -> position flat,
 *   spread margin left behind as house P&L.
 */
@Testcontainers
class EndToEndTest {

    static final class FixedProvider implements RateProvider {
        @Override
        public String id() {
            return "alpha";
        }

        @Override
        public BigDecimal rate(String fromCurrency, String toCurrency) {
            if (fromCurrency.equals("GBP") && toCurrency.equals("EUR")) {
                return new BigDecimal("1.150000");
            }
            throw new IllegalStateException("unquoted pair");
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static com.pucilowski.ledger.App ledger;
    static App fx;
    static HttpClient client;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        var dataSource = com.pucilowski.ledger.Database.connect(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ledger = new com.pucilowski.ledger.App(dataSource, 0);
        var config = new Config(
                "dev-secret",                       // both services' default secret
                Duration.ofSeconds(30),
                20,
                "http://localhost:" + ledger.port(),
                new UUID(0, 1),
                new BigDecimal("100.00"),           // hedge when short by more than 100
                Duration.ofMillis(200),
                true);
        fx = new App(0, config, List.of(new FixedProvider()), Clock.systemUTC());
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        fx.stop();
        ledger.stop();
        client.close();
    }

    @Test
    void quoteConvertHedgeRoundTrip() throws Exception {
        // A customer with pounds who wants euros.
        var owner = UUID.randomUUID();
        var gbp = ledgerAccount(owner, "GBP");
        var eur = ledgerAccount(owner, "EUR");
        ledgerPost("/accounts/" + gbp + "/deposits", """
                {"amount": "1000.00"}""");

        // Quote: 1.15 market minus 20bps spread = 1.1477 -> 918.16 EUR for 800 GBP.
        var quoteResponse = fxPost("/quotes", """
                {"fromCurrency": "GBP", "toCurrency": "EUR", "fromAmount": "800.00"}""");
        assertThat(quoteResponse.statusCode()).isEqualTo(201);
        var quote = quoteResponse.body();
        assertThat(new BigDecimal(json.readTree(quote).get("toAmount").asText()))
                .isEqualByComparingTo(new BigDecimal("918.16"));

        // Convert on the ledger, quote passed through verbatim.
        var convert = ledgerPost("/convert", """
                {"quote": %s, "fromAccountId": "%s", "toAccountId": "%s"}"""
                .formatted(quote, gbp, eur));
        assertThat(convert.statusCode()).isEqualTo(201);
        assertThat(balance(gbp)).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(balance(eur)).isEqualByComparingTo(new BigDecimal("918.16"));

        // A quote spends once.
        assertThat(ledgerPost("/convert", """
                {"quote": %s, "fromAccountId": "%s", "toAccountId": "%s"}"""
                .formatted(quote, gbp, eur)).statusCode()).isEqualTo(409);

        // The house went short 918.16 EUR — beyond the 100 threshold — so the
        // hedger must flatten it: sell GBP at 1.15 (918.16 / 1.15 = 798.40),
        // leaving the 1.60 GBP spread margin as house position.
        var deadline = Instant.now().plus(Duration.ofSeconds(15));
        Map<String, BigDecimal> positions = Map.of();
        while (Instant.now().isBefore(deadline)) {
            positions = fxPositions();
            var eurPosition = positions.getOrDefault("EUR", BigDecimal.ZERO);
            if (eurPosition.compareTo(BigDecimal.ZERO) == 0 && positions.containsKey("GBP")) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(positions.getOrDefault("EUR", BigDecimal.ZERO))
                .as("EUR position after hedging")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(positions.get("GBP"))
                .as("GBP spread margin left on the book")
                .isEqualByComparingTo(new BigDecimal("1.60"));

        // And through it all, money was conserved per currency.
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            var perCurrency = statement.executeQuery("""
                    select currency, sum(amount) as total
                    from journal_entry
                    group by currency
                    """);
            var seen = 0;
            while (perCurrency.next()) {
                seen++;
                assertThat(perCurrency.getBigDecimal("total"))
                        .as("net movement in %s", perCurrency.getString("currency"))
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
            assertThat(seen).isEqualTo(2); // GBP and EUR both moved, both conserved
        }
    }

    // -- helpers --

    static Map<String, BigDecimal> fxPositions() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + fx.port() + "/positions"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var node = json.readTree(response.body()).get("positions");
        var positions = new java.util.HashMap<String, BigDecimal>();
        node.fields().forEachRemaining(entry ->
                positions.put(entry.getKey(), new BigDecimal(entry.getValue().asText())));
        return positions;
    }

    static String ledgerAccount(UUID owner, String currency) throws Exception {
        var response = ledgerPost("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(owner, currency));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    static BigDecimal balance(String accountId) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + ledger.port() + "/accounts/" + accountId))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new BigDecimal(json.readTree(response.body()).get("balance").asText());
    }

    static HttpResponse<String> ledgerPost(String path, String body) throws Exception {
        return post("http://localhost:" + ledger.port() + path, body);
    }

    static HttpResponse<String> fxPost(String path, String body) throws Exception {
        return post("http://localhost:" + fx.port() + path, body);
    }

    static HttpResponse<String> post(String url, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
