package com.pucilowski.ledger;

import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * The single money-moving primitive. A journal is a list of entries that must
 * balance to zero per currency; posting one atomically locks the touched
 * accounts (in deterministic id order, so concurrent journals cannot
 * deadlock), applies the balance changes, and records the entries.
 */
public final class Journals {

    public record Entry(UUID accountId, String currency, BigDecimal amount) {
    }

    /**
     * Posts a journal inside the caller's transaction. Locks every touched
     * account with {@code select ... for update} in ascending account-id
     * order, verifies currencies and (for customer/nostro accounts)
     * non-negative resulting balances, then updates cached balances and
     * inserts the journal rows — all or nothing with whatever else the
     * caller does in the same transaction.
     */
    public static UUID post(DSLContext tx, String type, List<Entry> entries) {
        return post(tx, type, null, entries);
    }

    /**
     * As {@link #post(DSLContext, String, List)}, with an idempotency key
     * recorded on the journal. The key is unique in the schema, so replaying
     * the same instruction fails the transaction instead of moving money
     * twice; callers translate that violation into their idempotent response.
     */
    public static UUID post(DSLContext tx, String type, String idempotencyKey, List<Entry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a journal needs entries");
        }

        var perCurrency = new HashMap<String, BigDecimal>();
        for (var entry : entries) {
            perCurrency.merge(entry.currency(), entry.amount(), BigDecimal::add);
        }
        perCurrency.forEach((currency, sum) -> {
            if (sum.signum() != 0) {
                throw new IllegalArgumentException(
                        "journal does not balance in %s: off by %s".formatted(currency, sum));
            }
        });

        // Net delta per account, in ascending id order — the lock order.
        var deltas = new TreeMap<UUID, BigDecimal>();
        for (var entry : entries) {
            deltas.merge(entry.accountId(), entry.amount(), BigDecimal::add);
        }

        var accounts = new HashMap<UUID, Account>();
        for (var accountId : deltas.keySet()) {
            var record = tx.select().from(table("account"))
                    .where(field("id").eq(accountId))
                    .forUpdate()
                    .fetchOne();
            if (record == null) {
                throw new NotFoundException("no account " + accountId);
            }
            accounts.put(accountId, Accounts.toAccount(record));
        }

        for (var entry : entries) {
            var account = accounts.get(entry.accountId());
            if (!account.currency().equals(entry.currency())) {
                throw new ValidationException("account %s holds %s, not %s"
                        .formatted(account.id(), account.currency(), entry.currency()));
            }
        }

        // The journal row goes in before the balance checks: a replayed
        // instruction must fail on its idempotency key (it already ran),
        // not on whatever the balances happen to look like now.
        var journalId = UUID.randomUUID();
        tx.insertInto(table("journal"), field("id"), field("type"), field("idempotency_key"))
                .values(journalId, type, idempotencyKey)
                .execute();

        var newBalances = new TreeMap<UUID, BigDecimal>();
        for (var delta : deltas.entrySet()) {
            var account = accounts.get(delta.getKey());
            var newBalance = account.balance().add(delta.getValue());
            if (newBalance.signum() < 0 && mustStayNonNegative(account.kind())) {
                throw new InsufficientFundsException("insufficient funds in account " + account.id());
            }
            newBalances.put(account.id(), newBalance);
            tx.update(table("account"))
                    .set(field("balance"), newBalance)
                    .where(field("id").eq(account.id()))
                    .execute();
        }
        var insert = tx.insertInto(table("journal_entry"),
                field("id"), field("journal_id"), field("account_id"), field("currency"), field("amount"));
        for (var entry : entries) {
            insert = insert.values(UUID.randomUUID(), journalId, entry.accountId(), entry.currency(), entry.amount());
        }
        insert.execute();

        // Model events, in the same transaction as the change they describe.
        for (var delta : deltas.entrySet()) {
            var account = accounts.get(delta.getKey());
            Events.append(tx, journalId, "account", "BalanceChangedEvent", Map.of(
                    "accountId", account.id().toString(),
                    "ownerId", account.ownerId().toString(),
                    "currency", account.currency(),
                    "balance", newBalances.get(account.id()).toPlainString(),
                    "delta", delta.getValue().toPlainString(),
                    "journalId", journalId.toString()));
        }
        Events.append(tx, journalId, "transaction", "TransactionCompletedEvent", Map.of(
                "journalId", journalId.toString(),
                "type", type,
                "entries", entries.stream().map(entry -> Map.of(
                        "accountId", entry.accountId().toString(),
                        "currency", entry.currency(),
                        "amount", entry.amount().toPlainString())).toList()));
        Events.notifyPublisher(tx);

        return journalId;
    }

    private static boolean mustStayNonNegative(String kind) {
        return kind.equals("customer") || kind.equals("nostro");
    }

    private Journals() {
    }
}
