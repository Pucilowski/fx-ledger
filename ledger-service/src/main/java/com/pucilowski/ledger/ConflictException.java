package com.pucilowski.ledger;

/** The request conflicts with existing state — maps to HTTP 409. */
public final class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
