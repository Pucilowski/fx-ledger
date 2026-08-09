package com.pucilowski.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4: quotes. Best-execution across providers, spread in the house's
 * favour, short expiry, HMAC signature; failing providers are skipped and
 * eventually not called at all (circuit breaker). Each test gets a fresh
 * app so breaker state cannot leak between tests.
 */
class QuotesApiTest {

    static final class FakeProvider implements RateProvider {
        final String id;
        volatile BigDecimal rate;
        volatile boolean failing;
        final AtomicInteger calls = new AtomicInteger();

        FakeProvider(String id, String rate) {
            this.id = id;
            this.rate = new BigDecimal(rate);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BigDecimal rate(String fromCurrency, String toCurrency) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException(id + " is down");
            }
            return rate;
        }
    }

    FakeProvider alpha;
    FakeProvider beta;
    App app;
    HttpClient client;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() {
        alpha = new FakeProvider("alpha", "1.1000");
        beta = new FakeProvider("beta", "1.2000");
        var config = new Config("test-secret", Duration.ofSeconds(30), 20,
                "http://unused", new UUID(0, 1), new BigDecimal("100.00"),
                Duration.ofMillis(200), false);
        app = new App(0, config, List.of(alpha, beta), Clock.systemUTC());
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        app.stop();
        client.close();
    }

    @Test
    void quoteUsesBestProviderRateMinusSpread() throws Exception {
        var response = quote("GBP", "EUR", "100.00");

        assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body());
        // beta's 1.20 beats alpha's 1.10; 20bps spread comes off it
        assertThat(new BigDecimal(body.get("rate").asText()))
                .isEqualByComparingTo(new BigDecimal("1.1976"));
        assertThat(new BigDecimal(body.get("toAmount").asText()))
                .isEqualByComparingTo(new BigDecimal("119.76"));
        assertThat(body.get("signature").asText()).hasSize(64); // hmac-sha256 hex
        assertThat(Instant.parse(body.get("expiresAt").asText())).isAfter(Instant.now());
    }

    @Test
    void failingProviderIsSkippedForBestExecution() throws Exception {
        beta.failing = true;

        var response = quote("GBP", "EUR", "100.00");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(new BigDecimal(json.readTree(response.body()).get("rate").asText()))
                .isEqualByComparingTo(new BigDecimal("1.0978")); // alpha's 1.10 minus spread
    }

    @Test
    void circuitBreakerStopsCallingARepeatedlyFailingProvider() throws Exception {
        alpha.failing = true;

        for (int i = 0; i < 3; i++) {
            assertThat(quote("GBP", "EUR", "10.00").statusCode()).isEqualTo(201);
        }
        assertThat(alpha.calls.get()).isEqualTo(3); // breaker now open

        for (int i = 0; i < 3; i++) {
            assertThat(quote("GBP", "EUR", "10.00").statusCode()).isEqualTo(201);
        }
        assertThat(alpha.calls.get()).isEqualTo(3); // not called while open
    }

    @Test
    void noLiquidityIs503() throws Exception {
        alpha.failing = true;
        beta.failing = true;

        assertThat(quote("GBP", "EUR", "10.00").statusCode()).isEqualTo(503);
    }

    @Test
    void malformedRequestIs400() throws Exception {
        var response = post("/quotes", """
                {"fromCurrency": "GBP", "toCurrency": "EUR"}""");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // -- helpers --

    HttpResponse<String> quote(String from, String to, String amount) throws Exception {
        return post("/quotes", """
                {"fromCurrency": "%s", "toCurrency": "%s", "fromAmount": "%s"}"""
                .formatted(from, to, amount));
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
