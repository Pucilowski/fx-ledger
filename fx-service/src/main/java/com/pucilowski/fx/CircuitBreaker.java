package com.pucilowski.fx;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-provider circuit breaker: after enough consecutive failures the
 * provider is skipped for a cooldown period instead of being hammered while
 * it's down. Half-open by construction — once the cooldown passes, the next
 * call goes through, and its outcome closes or re-opens the breaker.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil = Instant.EPOCH;

    public CircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized boolean allows() {
        return !clock.instant().isBefore(openUntil);
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntil = clock.instant().plus(cooldown);
        }
    }
}
