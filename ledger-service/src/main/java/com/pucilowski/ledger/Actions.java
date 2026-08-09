package com.pucilowski.ledger;

import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * Business actions. Each runs in one transaction and moves money exclusively
 * through {@link Journals#post}, so every state change is a balanced journal.
 */
public final class Actions {

    /** The owner id of the per-currency accounts representing the outside world. */
    static final UUID EXTERNAL_OWNER = new UUID(0, 0);
    /** The owner id of the house accounts — the firm's own position, per currency. */
    static final UUID HOUSE_OWNER = new UUID(0, 1);

    private final DSLContext db;
    private final String quoteSecret;
    private final Clock clock;

    public Actions(DSLContext db, String quoteSecret, Clock clock) {
        this.db = db;
        this.quoteSecret = quoteSecret;
        this.clock = clock;
    }

    public Account deposit(UUID accountId, BigDecimal amount) {
        requirePositive(amount);
        return db.transactionResult(cfg -> {
            var tx = cfg.dsl();
            var account = Accounts.fetch(tx, accountId);
            var external = externalAccount(tx, account.currency());
            Journals.post(tx, "deposit", List.of(
                    new Journals.Entry(external, account.currency(), amount.negate()),
                    new Journals.Entry(accountId, account.currency(), amount)));
            return Accounts.fetch(tx, accountId);
        });
    }

    public Account withdraw(UUID accountId, BigDecimal amount) {
        requirePositive(amount);
        return db.transactionResult(cfg -> {
            var tx = cfg.dsl();
            var account = Accounts.fetch(tx, accountId);
            var external = externalAccount(tx, account.currency());
            Journals.post(tx, "withdrawal", List.of(
                    new Journals.Entry(accountId, account.currency(), amount.negate()),
                    new Journals.Entry(external, account.currency(), amount)));
            return Accounts.fetch(tx, accountId);
        });
    }

    public Account transfer(UUID fromId, UUID toId, BigDecimal amount) {
        requirePositive(amount);
        if (fromId.equals(toId)) {
            throw new ValidationException("cannot transfer an account to itself");
        }
        return db.transactionResult(cfg -> {
            var tx = cfg.dsl();
            var from = Accounts.fetch(tx, fromId);
            var to = Accounts.fetch(tx, toId);
            if (!from.currency().equals(to.currency())) {
                throw new ValidationException("cannot transfer %s to a %s account"
                        .formatted(from.currency(), to.currency()));
            }
            Journals.post(tx, "transfer", List.of(
                    new Journals.Entry(fromId, from.currency(), amount.negate()),
                    new Journals.Entry(toId, to.currency(), amount)));
            return Accounts.fetch(tx, fromId);
        });
    }

    /**
     * Executes a conversion against a signed quote. The quote is verified
     * locally (signature, expiry) — no call back to fx-service — and its id
     * becomes the journal's idempotency key, so a quote spends exactly once.
     * Four balanced entries: the customer's from-currency moves to the house,
     * the house's to-currency moves to the customer; whatever spread fx
     * priced in stays behind as house position.
     */
    public Account convert(Quotes quote, UUID fromAccountId, UUID toAccountId) {
        if (!quote.signatureValid(quoteSecret)) {
            throw new ValidationException("quote signature is invalid");
        }
        if (quote.expired(clock)) {
            throw new QuoteExpiredException("quote " + quote.id() + " has expired");
        }
        var fromAmount = parseAmount(quote.fromAmount());
        var toAmount = parseAmount(quote.toAmount());
        try {
            return db.transactionResult(cfg -> {
                var tx = cfg.dsl();
                var from = Accounts.fetch(tx, fromAccountId);
                var to = Accounts.fetch(tx, toAccountId);
                if (!from.ownerId().equals(to.ownerId())) {
                    throw new ValidationException("accounts belong to different owners");
                }
                if (!from.currency().equals(quote.fromCurrency())
                        || !to.currency().equals(quote.toCurrency())) {
                    throw new ValidationException("account currencies do not match the quote");
                }
                var houseFrom = houseAccount(tx, quote.fromCurrency());
                var houseTo = houseAccount(tx, quote.toCurrency());
                var journalId = Journals.post(tx, "conversion", "quote:" + quote.id(), List.of(
                        new Journals.Entry(fromAccountId, quote.fromCurrency(), fromAmount.negate()),
                        new Journals.Entry(houseFrom, quote.fromCurrency(), fromAmount),
                        new Journals.Entry(houseTo, quote.toCurrency(), toAmount.negate()),
                        new Journals.Entry(toAccountId, quote.toCurrency(), toAmount)));
                Events.append(tx, journalId, "conversion", "ConversionCompletedEvent", Map.of(
                        "journalId", journalId.toString(),
                        "quoteId", quote.id(),
                        "ownerId", from.ownerId().toString(),
                        "fromCurrency", quote.fromCurrency(),
                        "fromAmount", fromAmount.toPlainString(),
                        "toCurrency", quote.toCurrency(),
                        "toAmount", toAmount.toPlainString()));
                return Accounts.fetch(tx, fromAccountId);
            });
        } catch (IntegrityConstraintViolationException e) {
            throw new ConflictException("quote " + quote.id() + " has already been used");
        }
    }

    /**
     * Records a filled hedge as ledger truth: the house sold one currency to
     * the outside world and bought another. Idempotent on the hedge id, so
     * fx-service can safely retry.
     */
    public void hedgeSettlement(String hedgeId, String providerId,
                                String fromCurrency, BigDecimal fromAmount,
                                String toCurrency, BigDecimal toAmount) {
        requirePositive(fromAmount);
        requirePositive(toAmount);
        if (fromCurrency.equals(toCurrency)) {
            throw new ValidationException("a hedge exchanges two different currencies");
        }
        try {
            db.transaction(cfg -> {
                var tx = cfg.dsl();
                var houseFrom = houseAccount(tx, fromCurrency);
                var houseTo = houseAccount(tx, toCurrency);
                var externalFrom = externalAccount(tx, fromCurrency);
                var externalTo = externalAccount(tx, toCurrency);
                var journalId = Journals.post(tx, "hedge", "hedge:" + hedgeId, List.of(
                        new Journals.Entry(houseFrom, fromCurrency, fromAmount.negate()),
                        new Journals.Entry(externalFrom, fromCurrency, fromAmount),
                        new Journals.Entry(externalTo, toCurrency, toAmount.negate()),
                        new Journals.Entry(houseTo, toCurrency, toAmount)));
                Events.append(tx, journalId, "hedge", "HedgeSettledEvent", Map.of(
                        "journalId", journalId.toString(),
                        "hedgeId", hedgeId,
                        "providerId", providerId,
                        "fromCurrency", fromCurrency,
                        "fromAmount", fromAmount.toPlainString(),
                        "toCurrency", toCurrency,
                        "toAmount", toAmount.toPlainString()));
            });
        } catch (IntegrityConstraintViolationException e) {
            throw new ConflictException("hedge " + hedgeId + " has already been settled");
        }
    }

    private static UUID houseAccount(DSLContext tx, String currency) {
        return systemAccount(tx, HOUSE_OWNER, currency, "house");
    }

    private static UUID externalAccount(DSLContext tx, String currency) {
        return systemAccount(tx, EXTERNAL_OWNER, currency, "external");
    }

    private static BigDecimal parseAmount(String raw) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException("amount must be a decimal number");
        }
        requirePositive(amount);
        return amount;
    }

    /**
     * Get-or-create for the well-known system accounts (external, house).
     * Insert-or-skip, then select: concurrent first use of a currency must
     * not abort the transaction the way a unique violation would.
     */
    private static UUID systemAccount(DSLContext tx, UUID owner, String currency, String kind) {
        tx.insertInto(table("account"),
                        field("id"), field("owner_id"), field("currency"), field("kind"))
                .values(UUID.randomUUID(), owner, currency, kind)
                .onConflictDoNothing()
                .execute();
        return tx.select(field("id"))
                .from(table("account"))
                .where(field("owner_id").eq(owner), field("currency").eq(currency))
                .fetchOne()
                .get("id", UUID.class);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new ValidationException("amount must be positive");
        }
        if (amount.scale() > 4) {
            throw new ValidationException("amount has more than four decimal places");
        }
    }
}
