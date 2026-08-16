package com.pucilowski.fx;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The house's net position per currency, as a projection over the ledger's
 * event stream: the sum of every BalanceChangedEvent delta on house-owned
 * accounts. Nothing here is authoritative — crash, restart, replay the
 * stream, and the projection rebuilds to exactly the ledger's truth.
 *
 * The owner check is payload filtering done by hand; a fuller consumer SDK
 * would let the subscription declare it.
 */
public final class Positions implements EventConsumer {

    /** This consumer's slice of BalanceChangedEvent — only the fields it uses. */
    record BalanceChanged(UUID ownerId, String currency, BigDecimal delta) {
    }

    private final UUID houseOwner;
    private final Map<String, BigDecimal> byCurrency = new ConcurrentHashMap<>();

    public Positions(UUID houseOwner) {
        this.houseOwner = houseOwner;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("BalanceChangedEvent");
    }

    @Override
    public void on(LedgerClient.LedgerEvent event) {
        var change = LedgerClient.payload(event, BalanceChanged.class);
        if (!change.ownerId().equals(houseOwner)) {
            return;
        }
        byCurrency.merge(change.currency(), change.delta(), BigDecimal::add);
    }

    public Map<String, BigDecimal> snapshot() {
        return Map.copyOf(byCurrency);
    }
}
