package com.pucilowski.fx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues firm quotes: best provider rate minus our spread, valid for a short
 * window, HMAC-signed so the ledger can verify the quote locally. The expiry
 * is a risk parameter, not a nicety — a firm quote is a free option granted
 * to the customer, and the window bounds how much it can be worth.
 */
public final class Quotes {

    public record Quote(String id, String fromCurrency, String toCurrency,
                        String fromAmount, String toAmount, String rate,
                        String expiresAt, String signature) {
    }

    private final Providers providers;
    private final Config config;
    private final Clock clock;

    public Quotes(Providers providers, Config config, Clock clock) {
        this.providers = providers;
        this.config = config;
        this.clock = clock;
    }

    public Quote create(String fromCurrency, String toCurrency, BigDecimal fromAmount) {
        var best = providers.best(fromCurrency, toCurrency)
                .orElseThrow(() -> new NoLiquidityException(
                        "no provider can price " + fromCurrency + "/" + toCurrency));

        // Customer rate = best market rate minus our spread; rounding is
        // always in the house's favour.
        var customerRate = best.rate()
                .multiply(BigDecimal.valueOf(10_000 - config.spreadBps(), 4))
                .setScale(6, RoundingMode.DOWN);
        var toAmount = fromAmount.multiply(customerRate).setScale(4, RoundingMode.DOWN);

        var id = UUID.randomUUID().toString();
        var expiresAt = clock.instant().plus(config.quoteTtl()).toString();
        var canonical = String.join("|",
                id, fromCurrency, toCurrency,
                fromAmount.toPlainString(), toAmount.toPlainString(), expiresAt);
        return new Quote(id, fromCurrency, toCurrency,
                fromAmount.toPlainString(), toAmount.toPlainString(),
                customerRate.toPlainString(), expiresAt,
                sign(config.quoteSecret(), canonical));
    }

    static String sign(String secret, String canonical) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
