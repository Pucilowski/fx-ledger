package com.pucilowski.fx;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

public record Config(
        String quoteSecret,
        Duration quoteTtl,
        int spreadBps,
        String ledgerUrl,
        UUID houseOwner,
        BigDecimal hedgeThreshold,
        Duration pollInterval,
        boolean hedging) {

    public static Config defaults() {
        return new Config(
                "dev-secret",
                Duration.ofSeconds(30),
                20,
                "http://localhost:8080",
                new UUID(0, 1),
                new BigDecimal("100.00"),
                Duration.ofMillis(500),
                true);
    }
}
