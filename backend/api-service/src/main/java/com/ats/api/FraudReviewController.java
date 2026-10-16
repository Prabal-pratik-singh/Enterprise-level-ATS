package com.ats.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.ApplicationStatus;
import com.ats.domain.Score;
import com.ats.domain.ScoreRepository;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The human half of the Trust Service: the machine only FLAGS, a person decides.
 * CLEAR = false alarm (penalty lifted, back in the ranking); CONFIRM = fraud (rejected).
 */
@RestController
@RequestMapping("/api")
public class FraudReviewController {

    private final CandidateQueryService query;
    private final ApplicationRepository applications;
    private final ScoreRepository scores;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public FraudReviewController(CandidateQueryService query, ApplicationRepository applications,
                                 ScoreRepository scores, ApplicationEventRepository auditTrail,
                                 OutboxPublisher outbox, ObjectMapper mapper) {
        this.query = query;
        this.applications = applications;
        this.scores = scores;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @GetMapping("/review-queue")
    public List<CandidateQueryService.CandidateRow> queue(@RequestParam(required = false) UUID jobId) {
        return query.flagged(jobId);
    }

    public record ReviewRequest(String verdict, String reason) {
    }

    @PostMapping("/candidates/{applicationId}/fraud-review")
    @Transactional
    public Map<String, Object> review(@PathVariable UUID applicationId, @RequestBody ReviewRequest req) {
        String verdict = req.verdict() == null ? "" : req.verdict().toUpperCase();
        if (!verdict.equals("CLEAR") && !verdict.equals("CONFIRM")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "verdict must be CLEAR or CONFIRM");
        }
        Application app = applications.findById(applicationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found"));
        if (app.getStatus() != ApplicationStatus.FLAGGED_FOR_REVIEW) {
            // only a pending flag can be reviewed — never silently undo a decision
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "application is " + app.getStatus() + ", not FLAGGED_FOR_REVIEW");
        }
        Score score = scores.findByApplicationId(applicationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "no trust verdict recorded"));

        ApplicationStatus next;
        if (verdict.equals("CLEAR")) {
            score.clearFraudPenalty();                  // flags stay as history; only the points come back
            next = score.getMatchScore() != null ? ApplicationStatus.SCORED : ApplicationStatus.EXTRACTED;
        } else {
            next = ApplicationStatus.REJECTED;          // penalty stays — the record shows why
        }
        app.transitionTo(next);
        auditTrail.save(new ApplicationEvent(applicationId,
                verdict.equals("CLEAR") ? "FRAUD_CLEARED" : "FRAUD_CONFIRMED", json(Map.of(
                        "fraud_score", score.getFraudScore(),
                        "reason", req.reason() == null ? "" : req.reason(),
                        "decided_by", "demo-recruiter")))); // single-recruiter demo — no auth by design
        // the indexer listens to this: the search copy shows the new status within ~2s
        outbox.append(Topics.SHORTLIST_UPDATED, app.getId(), app.getJobId(), Map.of(
                "status", next.name(), "decided_by", "demo-recruiter"));
        return Map.of("applicationId", applicationId, "status", next.name());
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize audit details", e);
        }
    }
}
