package com.pucilowski.ledger;

/** The operation would drive a protected account negative — maps to HTTP 422. */
public final class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
