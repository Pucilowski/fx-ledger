package com.pucilowski.ledger;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.table;

/**
 * Publishes outbox events to the stream: assigns each unpublished event the
 * next dense stream offset and fans it out to live subscribers.
 *
 * Two delivery paths share one idempotent {@link #publish()}:
 * the publisher proper wakes on LISTEN/NOTIFY and publishes promptly; the
 * reconciler sweeps on a timer and picks up anything the publisher missed
 * (lost notification, publisher down) — together, at-least-once delivery.
 * Running more than one instance would need the row selection fenced (e.g.
 * {@code for update skip locked}); a single instance only needs
 * {@code synchronized}.
 */
public final class EventPublisher {

    public record Config(boolean listenPublisher, Duration reconcilerInterval) {
        public static Config defaults() {
            return new Config(true, Duration.ofSeconds(2));
        }
    }

    private final DSLContext db;
    private final DataSource dataSource;
    private final Config config;
    private final List<BlockingQueue<Events.Event>> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread listener;
    private Thread reconciler;

    public EventPublisher(DSLContext db, DataSource dataSource, Config config) {
        this.db = db;
        this.dataSource = dataSource;
        this.config = config;
    }

    public void start() {
        if (config.listenPublisher()) {
            listener = Thread.ofVirtual().name("event-publisher").start(this::listenLoop);
        }
        reconciler = Thread.ofVirtual().name("event-reconciler").start(this::reconcileLoop);
    }

    public void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
        }
        if (reconciler != null) {
            reconciler.interrupt();
        }
    }

    /** A live subscription; the caller must {@link #unsubscribe} it. */
    public BlockingQueue<Events.Event> subscribe() {
        var queue = new ArrayBlockingQueue<Events.Event>(1024);
        subscribers.add(queue);
        return queue;
    }

    public void unsubscribe(BlockingQueue<Events.Event> queue) {
        subscribers.remove(queue);
    }

    private void listenLoop() {
        while (running) {
            try (var connection = Database.listenConnection(dataSource)) {
                try (var statement = connection.createStatement()) {
                    statement.execute("listen events");
                }
                publish(); // anything already pending when we connected
                var pg = connection.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications(500);
                    if (notifications != null && notifications.length > 0) {
                        publish();
                    }
                }
            } catch (Exception e) {
                if (running) {
                    sleepQuietly(1000); // connection lost: back off, reconnect
                }
            }
        }
    }

    private void reconcileLoop() {
        while (running) {
            sleepQuietly(config.reconcilerInterval().toMillis());
            if (running) {
                try {
                    publish();
                } catch (Exception e) {
                    // next sweep retries
                }
            }
        }
    }

    /**
     * Assigns stream offsets to all unpublished events, oldest first, then
     * broadcasts them to live subscribers. Idempotent: already-published
     * events are never selected again. Broadcast happens after commit so a
     * live subscriber can never see an event before the catch-up read can.
     */
    synchronized void publish() {
        var published = db.transactionResult(cfg -> {
            var tx = cfg.dsl();
            var rows = tx.select()
                    .from(table("event_log"))
                    .where(field("published_offset").isNull())
                    .orderBy(field("id"))
                    .forUpdate()
                    .fetch();
            if (rows.isEmpty()) {
                return List.<Events.Event>of();
            }
            var next = tx.select(coalesce(max(field("published_offset", Long.class)), inline(0L)))
                    .from(table("event_log"))
                    .fetchSingle()
                    .value1() + 1;
            var assigned = new ArrayList<Events.Event>();
            for (var row : rows) {
                tx.update(table("event_log"))
                        .set(field("published_offset"), next)
                        .set(field("published_at"), currentOffsetDateTime())
                        .where(field("id").eq(row.get("id")))
                        .execute();
                assigned.add(new Events.Event(
                        next,
                        row.get("model_type", String.class),
                        row.get("event_type", String.class),
                        row.get("payload", JSONB.class).data(),
                        row.get("created_at", OffsetDateTime.class)));
                next++;
            }
            return assigned;
        });
        for (var event : published) {
            for (var subscriber : subscribers) {
                subscriber.offer(event); // a full (stalled) subscriber misses live events
            }                            // and catches up via the offset feed
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
