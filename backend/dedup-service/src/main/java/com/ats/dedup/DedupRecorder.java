package com.ats.dedup;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that ends a dedup run: idempotency mark + signature + buckets +
 * matches (both sides) + audit row + application.deduped outbox events.
 * Dedup never changes a status — it records; the Trust Service decides what it costs.
 */
@Service
public class DedupRecorder {

    private final ProcessedEvents processed;
    private final DedupRepository repository;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public DedupRecorder(ProcessedEvents processed, DedupRepository repository,
                         ApplicationEventRepository auditTrail, OutboxPublisher outbox, ObjectMapper mapper) {
        this.processed = processed;
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void record(EventEnvelope event, DedupRepository.Subject subject, DuplicateFinder.Applicant me,
                       long[] signature, long[] buckets, DuplicateFinder.Result result) {
        if (!processed.markProcessed(event.eventId(), ResumeExtractedConsumer.CONSUMER_NAME)) {
            return;
        }
        UUID id = me.applicationId();
        repository.saveSignature(id, signature, result.clusterId());
        repository.replaceBuckets(id, buckets);
        repository.replaceMatches(me, result.findings());

        List<Map<String, Object>> summary = result.findings().stream().map(f -> Map.<String, Object>of(
                "kind", f.kind().name(),
                "other_application_id", f.other().applicationId().toString(),
                "similarity", f.similarity())).toList();
        auditTrail.save(new ApplicationEvent(id, "DEDUPED", json(Map.of(
                "cluster_id", result.clusterId().toString(),
                "findings", summary))));

        // the newcomer's own event (always — the spec's topic), and one per matched EARLIER
        // application: both sides of a copy deserve a fresh trust check
        outbox.append(Topics.APPLICATION_DEDUPED, id, subject.jobId(), Map.of(
                "cluster_id", result.clusterId().toString(),
                "has_findings", !result.findings().isEmpty(),
                "findings", summary));
        for (DuplicateFinder.Finding f : result.findings()) {
            UUID other = f.other().applicationId();
            outbox.append(Topics.APPLICATION_DEDUPED, other, repository.jobOf(other), Map.of(
                    "cluster_id", f.other().clusterId().toString(),
                    "has_findings", true,
                    "triggered_by", id.toString()));
        }
    }

    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        if (!processed.markProcessed(event.eventId(), "dedup-dlq")) {
            return;
        }
        auditTrail.save(new ApplicationEvent(event.applicationId(), "DEDUP_FAILED",
                json(Map.of("error", errorSummary))));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize dedup payload", e);
        }
    }
}
