package com.ats.matcher;

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
 * The one transaction that ends a scoring run: idempotency mark + scores
 * upsert + status SCORED + audit row + application.scored outbox event.
 */
@Service
public class ScoreRecorder {

    private final ProcessedEvents processed;
    private final ScoreRepository scores;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public ScoreRecorder(ProcessedEvents processed, ScoreRepository scores,
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
    public void recordScore(EventEnvelope event, ScoreCard card) {
        if (!processed.markProcessed(event.eventId(), ResumeExtractedConsumer.CONSUMER_NAME)) {
            return;
        }

        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));

        // Upsert: a re-score (resume v2, replayed event) updates in place
        Score score = scores.findByApplicationId(app.getId()).orElseGet(() -> new Score(app.getId()));
        score.updateMatch(card.finalScore(), json(card.components()), json(card.evidence()));
        scores.save(score);

        app.transitionTo(ApplicationStatus.SCORED);
        auditTrail.save(new ApplicationEvent(app.getId(), "SCORED", json(Map.of(
                "final_score", card.finalScore(),
                "knocked_out", card.knockedOut(),
                "must_have_cap", card.mustHaveCap()))));

        // The api-service listens to this for SSE pushes; Phase 7's indexer joins later.
        outbox.append(Topics.APPLICATION_SCORED, app.getId(), app.getJobId(), Map.of(
                "score_id", score.getId().toString(),
                "candidate_id", app.getCandidateId().toString(),
                "final_score", card.finalScore(),
                "knocked_out", card.knockedOut()));
    }

    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        if (!processed.markProcessed(event.eventId(), "matcher-dlq")) {
            return;
        }
        // No SCORE_FAILED status in the state machine: stays EXTRACTED + audit, replayable.
        auditTrail.save(new ApplicationEvent(event.applicationId(), "SCORE_FAILED",
                json(Map.of("error", errorSummary))));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize score payload", e);
        }
    }
}
