package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/**
 * A quote issued by fx-service: a firm, short-lived, HMAC-signed commitment
 * to a rate. The signature lets this service verify the quote is genuine,
 * unaltered and single-purpose without calling fx-service — the quote itself
 * carries fx's authority. Amount fields stay raw strings: the signature
 * covers the exact bytes fx signed, so re-formatting would break it.
 */
public record Quotes(String id, String fromCurrency, String toCurrency,
                     String fromAmount, String toAmount, String expiresAt, String signature) {

    static Quotes fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ValidationException("missing quote");
        }
        return new Quotes(
                Json.required(node, "id"),
                Json.required(node, "fromCurrency"),
                Json.required(node, "toCurrency"),
                Json.required(node, "fromAmount"),
                Json.required(node, "toAmount"),
                Json.required(node, "expiresAt"),
                Json.required(node, "signature"));
    }

    boolean signatureValid(String secret) {
        var expected = sign(secret, canonical());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    boolean expired(Clock clock) {
        try {
            return Instant.parse(expiresAt).isBefore(clock.instant());
        } catch (DateTimeParseException e) {
            throw new ValidationException("expiresAt must be an ISO-8601 instant");
        }
    }

    String canonical() {
        return String.join("|", id, fromCurrency, toCurrency, fromAmount, toAmount, expiresAt);
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
