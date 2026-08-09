package com.pucilowski.ledger;

/** The quote's validity window has passed — maps to HTTP 422. */
public final class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException(String message) {
        super(message);
    }
}
