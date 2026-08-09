package com.pucilowski.fx;

import java.math.BigDecimal;

/** An external liquidity provider: streams rates, takes our hedge trades. */
public interface RateProvider {

    String id();

    /**
     * The provider's current rate for one unit of {@code fromCurrency} in
     * {@code toCurrency}. Throws on failure — providers are unreliable, and
     * the circuit breaker in front of each one deals with that.
     */
    BigDecimal rate(String fromCurrency, String toCurrency);
}
