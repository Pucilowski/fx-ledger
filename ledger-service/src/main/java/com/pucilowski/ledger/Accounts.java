package com.pucilowski.ledger;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public final class Accounts {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final DSLContext db;

    public Accounts(DSLContext db) {
        this.db = db;
    }

    public Account create(UUID ownerId, String currency) {
        return create(db, ownerId, currency, "customer");
    }

    public static Account create(DSLContext db, UUID ownerId, String currency, String kind) {
        if (!CURRENCY.matcher(currency).matches()) {
            throw new ValidationException("currency must be a three-letter code");
        }
        var id = UUID.randomUUID();
        try {
            db.insertInto(table("account"),
                            field("id"), field("owner_id"), field("currency"), field("kind"))
                    .values(id, ownerId, currency, kind)
                    .execute();
        } catch (IntegrityConstraintViolationException e) {
            throw new ConflictException("owner already has a %s account".formatted(currency));
        }
        return fetch(db, id);
    }

    public Account get(UUID id) {
        return fetch(db, id);
    }

    public static Account fetch(DSLContext db, UUID id) {
        var record = db.select().from(table("account")).where(field("id").eq(id)).fetchOne();
        if (record == null) {
            throw new NotFoundException("no account " + id);
        }
        return toAccount(record);
    }

    static Account toAccount(Record record) {
        return new Account(
                record.get("id", UUID.class),
                record.get("owner_id", UUID.class),
                record.get("currency", String.class),
                record.get("balance", BigDecimal.class),
                record.get("kind", String.class));
    }
}
