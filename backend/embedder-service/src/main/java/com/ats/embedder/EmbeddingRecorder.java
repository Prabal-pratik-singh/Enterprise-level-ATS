package com.ats.embedder;

import java.util.Map;

import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.CandidateProfile;
import com.ats.embedding.EmbeddingClient;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that ends an embedding run: idempotency mark + the vector
 * into candidate_profiles.embedding + audit row + resume.embedded outbox event.
 */
@Service
public class EmbeddingRecorder {

    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final EmbeddingClient embeddings;
    private final ObjectMapper mapper;

    public EmbeddingRecorder(ProcessedEvents processed, JdbcTemplate jdbc, ApplicationEventRepository auditTrail,
                             OutboxPublisher outbox, EmbeddingClient embeddings, ObjectMapper mapper) {
        this.processed = processed;
        this.jdbc = jdbc;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.embeddings = embeddings;
        this.mapper = mapper;
    }

    @Transactional
    public void record(EventEnvelope event, CandidateProfile profile, float[] vector, int chars) {
        if (!processed.markProcessed(event.eventId(), ResumeExtractedConsumer.CONSUMER_NAME)) {
            return;
        }
        // plain SQL: the JPA entity doesn't map the vector column, and pgvector reads "[0.1,...]" text
        jdbc.update("update candidate_profiles set embedding = ?::vector, updated_at = now() where id = ?",
                EmbeddingClient.toPgVector(vector), profile.getId());
        // no status change: the state machine has no EMBEDDED step — the audit row is the record
        auditTrail.save(new ApplicationEvent(profile.getApplicationId(), "EMBEDDED", json(Map.of(
                "model", embeddings.model(), "dimensions", vector.length, "text_chars", chars))));
        // the indexer (Batch 2) listens to this to put the vector into OpenSearch
        outbox.append(Topics.RESUME_EMBEDDED, profile.getApplicationId(), event.jobId(), Map.of(
                "profile_id", profile.getId().toString(),
                "candidate_id", profile.getCandidateId().toString(),
                "model", embeddings.model()));
    }

    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        if (!processed.markProcessed(event.eventId(), "embedder-dlq")) {
            return;
        }
        // status untouched — a resume without a vector is still scored; it just misses semantic search
        auditTrail.save(new ApplicationEvent(event.applicationId(), "EMBED_FAILED",
                json(Map.of("error", errorSummary))));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize embedding payload", e);
        }
    }
}
