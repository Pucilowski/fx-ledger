package com.pucilowski.fx;

import spark.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

public final class App {

    private final Service http;
    private final Hedger hedger;

    public App(int port) {
        this(port, Config.defaults(), defaultProviders(), Clock.systemUTC());
    }

    public App(int port, Config config, List<RateProvider> providers, Clock clock) {
        var panel = new Providers(providers, clock);
        var quotes = new Quotes(panel, config, clock);
        var positions = new Positions(config.houseOwner());

        if (config.hedging()) {
            this.hedger = new Hedger(new LedgerClient(config.ledgerUrl()), panel, positions, config, clock);
            this.hedger.start();
        } else {
            this.hedger = null;
        }

        this.http = Service.ignite().port(port);
        this.http.get("/health", (req, res) -> "OK");
        new Api(quotes, positions, hedger).routes(http);
        this.http.awaitInitialization();
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        if (hedger != null) {
            hedger.stop();
        }
        http.stop();
        http.awaitStop();
    }

    static List<RateProvider> defaultProviders() {
        return List.of(
                new SimulatedProvider("alpha", Map.of(
                        "GBP/EUR", new BigDecimal("1.1500"),
                        "GBP/USD", new BigDecimal("1.2700"),
                        "EUR/USD", new BigDecimal("1.1000")), 0.0005, 0.02),
                new SimulatedProvider("beta", Map.of(
                        "GBP/EUR", new BigDecimal("1.1497"),
                        "GBP/USD", new BigDecimal("1.2704"),
                        "EUR/USD", new BigDecimal("1.0998")), 0.0008, 0.05),
                new SimulatedProvider("gamma", Map.of(
                        "GBP/EUR", new BigDecimal("1.1503"),
                        "GBP/USD", new BigDecimal("1.2698"),
                        "EUR/USD", new BigDecimal("1.1002")), 0.0003, 0.10));
    }

    public static void main(String[] args) {
        var defaults = Config.defaults();
        var config = new Config(
                env("QUOTE_SECRET", defaults.quoteSecret()),
                defaults.quoteTtl(),
                defaults.spreadBps(),
                env("LEDGER_URL", defaults.ledgerUrl()),
                defaults.houseOwner(),
                new BigDecimal(env("HEDGE_THRESHOLD", defaults.hedgeThreshold().toPlainString())),
                defaults.pollInterval(),
                true);
        new App(Integer.parseInt(env("PORT", "8081")), config, defaultProviders(), Clock.systemUTC());
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
