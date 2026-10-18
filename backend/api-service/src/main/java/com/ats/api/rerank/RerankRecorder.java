package com.ats.api.rerank;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.Score;
import com.ats.domain.ScoreRepository;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One candidate's rerank in one transaction: score nudge + reasons + audit row + shortlist.updated. */
@Service
public class RerankRecorder {

    private final ScoreRepository scores;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public RerankRecorder(ScoreRepository scores, ApplicationRepository applications,
                          ApplicationEventRepository auditTrail, OutboxPublisher outbox, ObjectMapper mapper) {
        this.scores = scores;
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void record(UUID jobId, RerankPrompts.Verdict verdict) throws Exception {
        Score score = scores.findByApplicationId(verdict.applicationId()).orElse(null);
        Application app = applications.findById(verdict.applicationId()).orElse(null);
        if (score == null || app == null) {
            return;   // vanished meanwhile — nothing to nudge
        }
        double adjustment = RerankPrompts.clamp(verdict.adjustment());
        score.updateRerank(adjustment, mapper.writeValueAsString(verdict.reasons()));
        scores.save(score);
        auditTrail.save(new ApplicationEvent(app.getId(), "RERANKED", mapper.writeValueAsString(Map.of(
                "adjustment_percent", Math.round(adjustment * 100),
                "model", verdict.reasons().path("model").asText()))));
        // the indexer refreshes the search copy; the SSE listener pushes the new row to open pages
        outbox.append(Topics.SHORTLIST_UPDATED, app.getId(), jobId, Map.of(
                "status", app.getStatus().name(), "reason", "reranked"));
    }
}
