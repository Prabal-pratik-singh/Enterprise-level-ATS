package com.ats.fraud;

import java.util.List;
import java.util.Map;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.ApplicationStatus;
import com.ats.domain.Score;
import com.ats.domain.ScoreRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that ends a trust check: idempotency mark + the fraud half
 * of the scores row + status + audit row + application.flagged outbox event.
 * It FLAGS for a human; it never rejects anyone.
 */
@Service
public class TrustRecorder {

    private final ProcessedEvents processed;
    private final ScoreRepository scores;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public TrustRecorder(ProcessedEvents processed, ScoreRepository scores,
                         ApplicationRepository applications, ApplicationEventRepository auditTrail,
                         OutboxPublisher outbox, ObjectMapper mapper) {
        this.processed = processed;
        this.scores = scores;
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void recordVerdict(EventEnvelope event, TrustScorer.TrustVerdict verdict) {
        if (!processed.markProcessed(event.eventId(), ResumeExtractedConsumer.CONSUMER_NAME)) {
            return;
        }
        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));

        // Upsert. If the matcher creates the row at the very same moment, the unique
        // application_id makes one insert fail; Kafka retries 1s later and we update instead.
        Score score = scores.findByApplicationId(app.getId()).orElseGet(() -> new Score(app.getId()));
        score.updateTrust(verdict.fraudScore(), verdict.penalty(), json(verdict.flags()));
        scores.save(score);

        ApplicationStatus before = app.getStatus();
        ApplicationStatus after = nextStatus(before, verdict.flagged(), score.getMatchScore() != null);
        if (after != before) {
            app.transitionTo(after);
        }
        List<String> codes = verdict.flags().stream().map(FraudFlag::code).toList();
        auditTrail.save(new ApplicationEvent(app.getId(), "TRUST_CHECKED", json(Map.of(
                "fraud_score", verdict.fraudScore(),
                "penalty", verdict.penalty(),
                "flagged", verdict.flagged(),
                "flags", codes,
                "status_kept", after == before && verdict.flagged() ? before.name() : ""))));

        if (verdict.flagged()) {
            // the api-service pushes this to the dashboard (Batch 3)
            outbox.append(Topics.APPLICATION_FLAGGED, app.getId(), app.getJobId(), Map.of(
                    "score_id", score.getId().toString(),
                    "candidate_id", app.getCandidateId().toString(),
                    "fraud_score", verdict.fraudScore(),
                    "penalty", verdict.penalty(),
                    "flags", codes));
        }
    }

    /**
     * Where the status goes. Only pipeline-owned statuses ever move:
     * SHORTLISTED / REJECTED are a human's decision and are never overwritten.
     */
    static ApplicationStatus nextStatus(ApplicationStatus before, boolean flagged, boolean hasMatchScore) {
        if (flagged) {
            return before == ApplicationStatus.EXTRACTED || before == ApplicationStatus.SCORED
                    ? ApplicationStatus.FLAGGED_FOR_REVIEW : before;
        }
        if (before == ApplicationStatus.FLAGGED_FOR_REVIEW) {
            // a re-check (new resume version) came back clean: back to where the pipeline would be
            return hasMatchScore ? ApplicationStatus.SCORED : ApplicationStatus.EXTRACTED;
        }
        return before;
    }

    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        if (!processed.markProcessed(event.eventId(), "fraud-dlq")) {
            return;
        }
        // Status untouched: a failed trust check must not block the candidate. Audit only.
        auditTrail.save(new ApplicationEvent(event.applicationId(), "TRUST_CHECK_FAILED",
                json(Map.of("error", errorSummary))));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize trust payload", e);
        }
    }
}
