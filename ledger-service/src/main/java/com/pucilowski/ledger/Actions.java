package com.pucilowski.ledger;

import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.util.List;
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

    private final DSLContext db;

    public Actions(DSLContext db) {
        this.db = db;
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

    private static UUID externalAccount(DSLContext tx, String currency) {
        // Insert-or-skip, then select: concurrent first use of a currency must
        // not abort the transaction the way a unique violation would.
        tx.insertInto(table("account"),
                        field("id"), field("owner_id"), field("currency"), field("kind"))
                .values(UUID.randomUUID(), EXTERNAL_OWNER, currency, "external")
                .onConflictDoNothing()
                .execute();
        return tx.select(field("id"))
                .from(table("account"))
                .where(field("owner_id").eq(EXTERNAL_OWNER), field("currency").eq(currency))
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
