package com.pucilowski.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record Account(UUID id, UUID ownerId, String currency, BigDecimal balance, String kind) {
}
