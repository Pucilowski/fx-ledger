package com.pucilowski.ledger;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The event schema, in one place. Sealed so the compiler knows every event
 * type that exists; records so construction is field-checked; serialized 1:1
 * into the payload column.
 *
 * The wire contract stays JSON plus the model-type/event-type names —
 * consumers are expected to read tolerantly (declare only the fields they
 * use, ignore the rest), NOT to compile against this file. Sharing these
 * types across the service boundary would re-couple at build time what the
 * event stream decouples at runtime.
 *
 * Money fields serialize as strings: decimal amounts as JSON numbers invite
 * floating-point parsing on the consumer side.
 */
public sealed interface ModelEvent {

    String modelType();

    default String eventType() {
        return getClass().getSimpleName();
    }

    record BalanceChangedEvent(
            UUID accountId,
            UUID ownerId,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal delta,
            UUID journalId) implements ModelEvent {
        @Override
        public String modelType() {
            return "account";
        }
    }

    record TransactionCompletedEvent(
            UUID journalId,
            String type,
            List<EntryLine> entries) implements ModelEvent {
        @Override
        public String modelType() {
            return "transaction";
        }
    }

    record EntryLine(
            UUID accountId,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {
    }

    record ConversionCompletedEvent(
            UUID journalId,
            String quoteId,
            UUID ownerId,
            String fromCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fromAmount,
            String toCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal toAmount) implements ModelEvent {
        @Override
        public String modelType() {
            return "conversion";
        }
    }

    record HedgeSettledEvent(
            UUID journalId,
            String hedgeId,
            String providerId,
            String fromCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fromAmount,
            String toCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal toAmount) implements ModelEvent {
        @Override
        public String modelType() {
            return "hedge";
        }
    }
}
