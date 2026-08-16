package com.pucilowski.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The netting policy, and nothing else: when the book runs too short in a
 * currency, fire one aggregate hedge that flattens the short by selling the
 * longest currency at the best provider's rate. Consumption mechanics live
 * in {@link EventProcessor}; this class is called with events it declared
 * and, once the projection is current, asked whether to act.
 *
 * Customer conversions are never hedged individually — opposite flows cancel
 * on the book, and only the residual net position ever reaches a provider.
 * The threshold is the risk-appetite dial.
 */
public final class Hedger implements EventConsumer {

    /** This consumer's slice of HedgeSettledEvent. */
    record HedgeSettled(String hedgeId) {
    }

    private final LedgerClient ledger;
    private final Providers providers;
    private final Positions positions;
    private final Config config;
    private final Clock clock;

    // At most one hedge in flight: between settling a hedge and consuming its
    // events, the projection still looks over-limit, and without this guard
    // the policy would fire duplicate hedges at fresh ids — beyond what the
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

    @Override
    public Set<String> eventTypes() {
        return Set.of("HedgeSettledEvent");
    }

    @Override
    public void on(LedgerClient.LedgerEvent event) {
        if (LedgerClient.payload(event, HedgeSettled.class).hedgeId().equals(pendingHedgeId)) {
            pendingHedgeId = null;
        }
    }

    /** Runs only when the projection is caught up to the head of the stream. */
    void maybeHedge() throws Exception {
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
            return; // no liquidity right now; next pass retries
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
}
