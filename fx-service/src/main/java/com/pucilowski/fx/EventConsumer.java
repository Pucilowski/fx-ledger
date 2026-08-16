package com.pucilowski.fx;

import java.util.Set;

/**
 * A consumer of ledger events: declares which event types it wants and
 * receives each matching event in stream order. Policy lives here;
 * everything about HOW events arrive (polling, offsets, batching, catch-up)
 * lives in {@link EventProcessor}.
 */
public interface EventConsumer {

    Set<String> eventTypes();

    void on(LedgerClient.LedgerEvent event);
}
