package com.pucilowski.fx;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The house's net position per currency, as a projection over the ledger's
 * event stream: the sum of every BalanceChangedEvent delta on house-owned
 * accounts. Nothing here is authoritative — crash, restart, replay the
 * stream, and the projection rebuilds to exactly the ledger's truth.
 */
public final class Positions {

    private final UUID houseOwner;
    private final Map<String, BigDecimal> byCurrency = new ConcurrentHashMap<>();

    public Positions(UUID houseOwner) {
        this.houseOwner = houseOwner;
    }

    public void apply(LedgerClient.LedgerEvent event) {
        if (!event.eventType().equals("BalanceChangedEvent")) {
            return;
        }
        if (!event.payload().get("ownerId").asText().equals(houseOwner.toString())) {
            return;
        }
        var currency = event.payload().get("currency").asText();
        var delta = new BigDecimal(event.payload().get("delta").asText());
        byCurrency.merge(currency, delta, BigDecimal::add);
    }

    public Map<String, BigDecimal> snapshot() {
        return Map.copyOf(byCurrency);
    }
}
