package com.ats.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.ApplicationStatus;
import com.ats.domain.CandidateProfileRepository;
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

/** Everything the recruiter screen needs beyond the upload flow. */
@RestController
@RequestMapping("/api")
public class RecruiterController {

    private final CandidateQueryService query;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final CandidateProfileRepository profiles;
    private final ScoreRepository scores;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public RecruiterController(CandidateQueryService query, ApplicationRepository applications,
                               ApplicationEventRepository auditTrail, CandidateProfileRepository profiles,
                               ScoreRepository scores, OutboxPublisher outbox, ObjectMapper mapper) {
        this.query = query;
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.profiles = profiles;
        this.scores = scores;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** The ranked table. sort=score is the only (and default) order for now. */
    @GetMapping("/jobs/{jobId}/candidates")
    public Map<String, Object> candidates(@PathVariable UUID jobId,
                                          @RequestParam(defaultValue = "score") String sort,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", query.countForJob(jobId));
        response.put("page", page);
        response.put("size", size);
        response.put("items", query.pageForJob(jobId, page, Math.min(size, 200)));
        return response;
    }

    /** Everything about one candidate: profile, score breakdown, evidence, audit trail. */
    @GetMapping("/candidates/{applicationId}/report")
    public Map<String, Object> report(@PathVariable UUID applicationId) {
        Application app = applications.findById(applicationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", query.byApplicationId(applicationId).orElse(null));
        profiles.findByApplicationId(applicationId)
                .ifPresent(p -> report.put("profile", readTree(p.getProfile())));
        scores.findByApplicationId(applicationId).ifPresent(s -> {
            report.put("evidence", readTree(s.getEvidence()));
            report.put("components", readTree(s.getComponents()));
            report.put("match_score", s.getMatchScore());
            report.put("fraud_penalty", s.getFraudPenalty());   // match_score - fraud_penalty = final score
            report.put("fraud_score", s.getFraudScore());
            report.put("rerank_adjustment", s.getRerankAdjustment());
            report.put("llm_summary", readTree(s.getLlmSummary()));   // the AI review, if a rerank ran
            report.put("flags", readTree(s.getFlags()));
        });
        report.put("timeline", auditTrail.findByApplicationIdOrderByCreatedAtAsc(applicationId).stream()
                .map(e -> Map.of("event", e.getEventType(), "details", readTree(e.getDetails()),
                        "at", e.getCreatedAt()))
                .toList());
        return report;
    }

    public record DecisionRequest(String decision, String reason) {
    }

    /** The human's call — the system only ever narrows; a person decides. */
    @PostMapping("/candidates/{applicationId}/decision")
    @Transactional
    public Map<String, Object> decide(@PathVariable UUID applicationId, @RequestBody DecisionRequest req) {
        ApplicationStatus decision;
        try {
            decision = ApplicationStatus.valueOf(req.decision() == null ? "" : req.decision().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be SHORTLISTED or REJECTED");
        }
        if (decision != ApplicationStatus.SHORTLISTED && decision != ApplicationStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be SHORTLISTED or REJECTED");
        }
        Application app = applications.findById(applicationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found"));

        String from = app.getStatus().name();
        app.transitionTo(decision);
        auditTrail.save(new ApplicationEvent(applicationId, decision.name(), json(Map.of(
                "from", from,
                "reason", req.reason() == null ? "" : req.reason(),
                "decided_by", "demo-recruiter")))); // single-recruiter demo — no auth by design

        // the indexer listens to this: the search copy shows the new status within ~2s
        outbox.append(Topics.SHORTLIST_UPDATED, app.getId(), app.getJobId(), Map.of(
                "status", decision.name(), "decided_by", "demo-recruiter"));
        return Map.of("applicationId", applicationId, "status", decision.name());
    }

    private Object readTree(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize audit details", e);
        }
    }
}
