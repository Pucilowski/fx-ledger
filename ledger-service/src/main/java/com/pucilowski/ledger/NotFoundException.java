package com.pucilowski.ledger;

/** A referenced resource does not exist — maps to HTTP 404. */
public final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
