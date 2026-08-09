package com.pucilowski.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The netting loop. Consumes the ledger's event stream, maintains the house
 * position projection, and when the book runs too short in a currency, fires
 * one aggregate hedge that flattens the short by selling the longest
 * currency at the best provider's rate.
 *
 * Customer conversions are never hedged individually — opposite flows cancel
 * on the book, and only the residual net position ever reaches a provider.
 * The threshold is the risk-appetite dial: below it the house carries the
 * exposure and keeps the spread; above it, safety wins.
 */
public final class Hedger {

    private final LedgerClient ledger;
    private final Providers providers;
    private final Positions positions;
    private final Config config;
    private final Clock clock;

    private volatile boolean running = true;
    private volatile long offset;
    private Thread loop;

    // At most one hedge in flight: between settling a hedge and consuming its
    // events, the projection still looks over-limit, and without this guard
    // the loop would fire duplicate hedges at fresh ids — beyond what the
    // idempotency key can catch.
    private String pendingHedgeId;
    private Instant pendingSince = Instant.EPOCH;

    public Hedger(LedgerClient ledger, Providers providers, Positions positions,
                  Config config, Clock clock) {
        this.ledger = ledger;
        this.providers = providers;
        this.positions = positions;
        this.config = config;
        this.clock = clock;
    }

    public void start() {
        loop = Thread.ofVirtual().name("hedger").start(() -> {
            while (running) {
                try {
                    poll();
                } catch (Exception e) {
                    // ledger unreachable — next tick retries
                }
                sleepQuietly(config.pollInterval().toMillis());
            }
        });
    }

    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
    }

    public long offset() {
        return offset;
    }

    void poll() throws Exception {
        var batch = ledger.events(offset, 500);
        for (var event : batch) {
            positions.apply(event);
            if (event.eventType().equals("HedgeSettledEvent")
                    && event.payload().get("hedgeId").asText().equals(pendingHedgeId)) {
                pendingHedgeId = null;
            }
            offset = event.offset();
        }
        maybeHedge();
    }

    private void maybeHedge() throws Exception {
        if (pendingHedgeId != null
                && pendingSince.plus(Duration.ofSeconds(10)).isAfter(clock.instant())) {
            return;
        }
        var snapshot = positions.snapshot();
        var shortest = extreme(snapshot, false);
        var longest = extreme(snapshot, true);
        if (shortest == null || longest == null) {
            return;
        }
        var shortAmount = snapshot.get(shortest).negate();
        if (shortAmount.compareTo(config.hedgeThreshold()) <= 0
                || snapshot.get(longest).signum() <= 0) {
            return;
        }

        var best = providers.best(longest, shortest).orElse(null);
        if (best == null) {
            return; // no liquidity right now; next tick retries
        }
        var toAmount = shortAmount.setScale(4, RoundingMode.HALF_UP);
        var fromAmount = toAmount.divide(best.rate(), 4, RoundingMode.HALF_UP);
        var available = snapshot.get(longest);
        if (fromAmount.compareTo(available) > 0) {
            fromAmount = available.setScale(4, RoundingMode.DOWN);
            toAmount = fromAmount.multiply(best.rate()).setScale(4, RoundingMode.DOWN);
            if (toAmount.signum() <= 0) {
                return;
            }
        }

        var hedgeId = UUID.randomUUID().toString();
        ledger.settleHedge(hedgeId, best.providerId(), longest, fromAmount, shortest, toAmount);
        pendingHedgeId = hedgeId;
        pendingSince = clock.instant();
    }

    private static String extreme(Map<String, BigDecimal> snapshot, boolean largest) {
        String currency = null;
        BigDecimal value = null;
        for (var entry : snapshot.entrySet()) {
            if (value == null
                    || (largest ? entry.getValue().compareTo(value) > 0
                                : entry.getValue().compareTo(value) < 0)) {
                currency = entry.getKey();
                value = entry.getValue();
            }
        }
        return currency;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
