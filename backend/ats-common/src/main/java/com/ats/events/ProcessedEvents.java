package com.ats.events;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency ledger over the processed_events table (primary key: event_id +
 * consumer). Kafka + the outbox give us AT-LEAST-ONCE delivery — the same
 * event can arrive twice. This class makes "twice" harmless:
 *
 *   1. alreadyProcessed(...) — cheap pre-check BEFORE heavy work (S3, OCR, LLM)
 *   2. markProcessed(...)    — called INSIDE the work transaction, so the mark
 *                              commits or rolls back together with the writes
 *
 * If the app crashes after the work but before the commit, nothing was marked
 * and the retry redoes everything — safe, because all our writes are upserts.
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc; // plain SQL is clearer than JPA for these two tiny queries

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Have we fully handled this event before? Used to skip duplicates early. */
    public boolean alreadyProcessed(UUID eventId, String consumer) {
        Integer n = jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ? and consumer = ?",
                Integer.class, eventId, consumer);
        return n != null && n > 0;
    }

    /**
     * Claim this event. "on conflict do nothing" means: if another instance of
     * the same consumer inserted the row a millisecond earlier, our insert
     * affects 0 rows and we return false — the caller then skips its writes.
     * This is the race-proof half of the shield (the pre-check is just an optimization).
     */
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update(
                "insert into processed_events (event_id, consumer) values (?, ?) on conflict do nothing",
                eventId, consumer) == 1; // 1 row inserted = we own it; 0 = someone beat us
    }
}
