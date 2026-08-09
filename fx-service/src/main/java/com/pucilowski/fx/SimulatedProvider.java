package com.pucilowski.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A pretend liquidity provider: base rates with a little noise, an occasional
 * failure. Real enough to exercise best-execution selection and the circuit
 * breakers.
 */
public final class SimulatedProvider implements RateProvider {

    private final String id;
    private final Map<String, BigDecimal> baseRates;
    private final double noise;
    private final double failureRate;

    public SimulatedProvider(String id, Map<String, BigDecimal> baseRates,
                             double noise, double failureRate) {
        this.id = id;
        this.baseRates = baseRates;
        this.noise = noise;
        this.failureRate = failureRate;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BigDecimal rate(String fromCurrency, String toCurrency) {
        var random = ThreadLocalRandom.current();
        if (random.nextDouble() < failureRate) {
            throw new IllegalStateException(id + " timed out");
        }
        var base = baseRates.get(fromCurrency + "/" + toCurrency);
        if (base == null) {
            var inverse = baseRates.get(toCurrency + "/" + fromCurrency);
            if (inverse == null) {
                throw new IllegalStateException(id + " does not quote " + fromCurrency + "/" + toCurrency);
            }
            base = BigDecimal.ONE.divide(inverse, 6, RoundingMode.HALF_UP);
        }
        var jitter = BigDecimal.valueOf(1 + (random.nextDouble() * 2 - 1) * noise);
        return base.multiply(jitter).setScale(6, RoundingMode.HALF_UP);
    }
}
