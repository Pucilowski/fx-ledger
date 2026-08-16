package com.pucilowski.ledger;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * The event log: model events describing state changes, written in the same
 * transaction as the change itself (transactional outbox). Only published
 * events — those the publisher has assigned a stream offset — are visible to
 * consumers.
 */
public final class Events {

    public record Event(long offset, String modelType, String eventType, String payload,
                        OffsetDateTime occurredAt) {
    }

    /** Appends a model event inside the caller's transaction. */
    static void append(DSLContext tx, UUID journalId, ModelEvent event) {
        tx.insertInto(table("event_log"),
                        field("journal_id"), field("model_type"), field("event_type"), field("payload"))
                .values(journalId, event.modelType(), event.eventType(), JSONB.valueOf(Json.write(event)))
                .execute();
    }

    /** Wakes the publisher; fires on commit, since NOTIFY is transactional. */
    static void notifyPublisher(DSLContext tx) {
        tx.execute("select pg_notify('events', '')");
    }

    /** The catch-up read: published events after the given offset, in order. */
    static List<Event> after(DSLContext db, long offset, int limit) {
        return db.select()
                .from(table("event_log"))
                .where(field("published_offset", Long.class).gt(offset))
                .orderBy(field("published_offset"))
                .limit(limit)
                .fetch(Events::toEvent);
    }

    static Event toEvent(Record record) {
        return new Event(
                record.get("published_offset", Long.class),
                record.get("model_type", String.class),
                record.get("event_type", String.class),
                record.get("payload", JSONB.class).data(),
                record.get("created_at", OffsetDateTime.class));
    }

    private Events() {
    }
}
