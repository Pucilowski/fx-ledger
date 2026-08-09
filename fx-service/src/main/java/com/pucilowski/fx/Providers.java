package com.pucilowski.fx;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Best execution across the provider panel: ask everyone still allowed by
 * their breaker, take the best rate (most to-currency per unit — best for
 * whoever receives the proceeds, customer quote and hedge alike).
 */
public final class Providers {

    public record Rate(String providerId, BigDecimal rate) {
    }

    private final List<RateProvider> providers;
    private final Map<String, CircuitBreaker> breakers = new LinkedHashMap<>();

    public Providers(List<RateProvider> providers, Clock clock) {
        this.providers = providers;
        for (var provider : providers) {
            breakers.put(provider.id(), new CircuitBreaker(3, Duration.ofSeconds(5), clock));
        }
    }

    public Optional<Rate> best(String fromCurrency, String toCurrency) {
        Rate best = null;
        for (var provider : providers) {
            var breaker = breakers.get(provider.id());
            if (!breaker.allows()) {
                continue;
            }
            try {
                var rate = provider.rate(fromCurrency, toCurrency);
                breaker.recordSuccess();
                if (best == null || rate.compareTo(best.rate()) > 0) {
                    best = new Rate(provider.id(), rate);
                }
            } catch (RuntimeException e) {
                breaker.recordFailure();
            }
        }
        return Optional.ofNullable(best);
    }
}
