package com.pucilowski.ledger;

/** Malformed or invalid input — maps to HTTP 400. */
public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
