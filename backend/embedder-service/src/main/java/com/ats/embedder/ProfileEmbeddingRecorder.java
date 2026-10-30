package com.ats.embedder;

import java.util.Map;
import java.util.UUID;

import com.ats.embedding.EmbeddingClient;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that ends a profile embedding: ledger mark + the vector into
 * candidate_master_profiles.embedding + profile.embedded for the role classifier (C3).
 */
@Service
public class ProfileEmbeddingRecorder {

    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final EmbeddingClient embeddings;

    public ProfileEmbeddingRecorder(ProcessedEvents processed, JdbcTemplate jdbc, OutboxPublisher outbox,
                                    EmbeddingClient embeddings) {
        this.processed = processed;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.embeddings = embeddings;
    }

    @Transactional
    public void record(EventEnvelope event, UUID userId, String reason, float[] vector, int chars) {
        if (!processed.markProcessed(event.eventId(), ProfileChangedConsumer.CONSUMER_NAME)) {
            return;
        }
        // plain SQL: the JPA entity doesn't map the vector column, and pgvector reads "[0.1,...]" text
        jdbc.update("update candidate_master_profiles set embedding = ?::vector, updated_at = now() where user_id = ?",
                EmbeddingClient.toPgVector(vector), userId);
        outbox.appendForSubject(Topics.PROFILE_EMBEDDED, EventEnvelope.PROFILE, userId, Map.of(
                "reason", reason,
                "model", embeddings.model(),
                "dimensions", vector.length,
                "text_chars", chars));
    }
}
