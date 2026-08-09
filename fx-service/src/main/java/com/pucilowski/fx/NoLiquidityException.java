package com.pucilowski.fx;

/** No provider could price the pair — maps to HTTP 503. */
public final class NoLiquidityException extends RuntimeException {
    public NoLiquidityException(String message) {
        super(message);
    }
}
