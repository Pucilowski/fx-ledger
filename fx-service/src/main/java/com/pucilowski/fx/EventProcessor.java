package com.pucilowski.fx;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The consumption mechanics, separated from policy: polls the ledger's
 * event feed, dispatches each event to the registered {@link EventConsumer}s
 * in stream order, tracks the offset, and — once a poll returns a non-full
 * batch, meaning the projection is current — runs the caught-up hooks.
 * Consumers never see offsets, batches, or replay; hooks are guaranteed to
 * run only against a projection that reflects the head of the stream.
 *
 * Delivery is at-least-once: if a consumer throws mid-batch, the offset has
 * not advanced past the failed event and the next poll redelivers from
 * there. Consumers whose state is a projection heal fully on restart by
 * replaying from offset zero.
 */
public final class EventProcessor {

    public interface CaughtUpHook {
        void run() throws Exception;
    }

    private static final int BATCH = 500;

    private final LedgerClient ledger;
    private final Duration pollInterval;
    private final List<EventConsumer> consumers = new ArrayList<>();
    private final List<CaughtUpHook> caughtUpHooks = new ArrayList<>();

    private volatile boolean running = true;
    private volatile long offset;
    private Thread loop;

    public EventProcessor(LedgerClient ledger, Duration pollInterval) {
        this.ledger = ledger;
        this.pollInterval = pollInterval;
    }

    public void register(EventConsumer consumer) {
        consumers.add(consumer);
    }

    public void onCaughtUp(CaughtUpHook hook) {
        caughtUpHooks.add(hook);
    }

    public long offset() {
        return offset;
    }

    public void start() {
        loop = Thread.ofVirtual().name("event-processor").start(() -> {
            while (running) {
                try {
                    poll();
                } catch (Exception e) {
                    // ledger unreachable — next tick retries
                }
                sleepQuietly(pollInterval.toMillis());
            }
        });
    }

    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
    }

    void poll() throws Exception {
        var batch = ledger.events(offset, BATCH);
        for (var event : batch) {
            for (var consumer : consumers) {
                if (consumer.eventTypes().contains(event.eventType())) {
                    consumer.on(event);
                }
            }
            offset = event.offset();
        }
        if (batch.size() < BATCH) {
            for (var hook : caughtUpHooks) {
                try {
                    hook.run();
                } catch (Exception e) {
                    // a failing hook must not starve the others; next
                    // caught-up pass retries
                }
            }
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
