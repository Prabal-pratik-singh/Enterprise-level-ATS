package com.ats.extractor;

import java.util.Map;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.ApplicationStatus;
import com.ats.domain.CandidateProfile;
import com.ats.domain.CandidateProfileRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that ends an extraction: idempotency mark + profile
 * upsert + status EXTRACTED + audit row + resume.extracted outbox event.
 * All-or-nothing, same story as the parser's recorder.
 */
@Service
public class ExtractRecorder {

    private final ProcessedEvents processed;
    private final CandidateProfileRepository profiles;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public ExtractRecorder(ProcessedEvents processed, CandidateProfileRepository profiles,
                           ApplicationRepository applications, ApplicationEventRepository auditTrail,
                           OutboxPublisher outbox, ObjectMapper mapper) {
        this.processed = processed;
        this.profiles = profiles;
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void recordSuccess(EventEnvelope event, ProfileExtractor.ExtractedProfile result) {
        if (!processed.markProcessed(event.eventId(), ResumeParsedConsumer.CONSUMER_NAME)) {
            return; // another extractor instance won the race — drop our copy
        }

        // Application is loaded INSIDE the transaction (managed entity → auto-saved)
        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));

        // Upsert: re-extraction (resume v2) replaces the profile, never duplicates it
        String profileJson = result.profile().toString();
        CandidateProfile profile = profiles.findByApplicationId(app.getId())
                .map(existing -> {
                    existing.replaceProfile(profileJson, result.completeness());
                    return existing;
                })
                .orElseGet(() -> new CandidateProfile(app.getId(), app.getCandidateId(),
                        profileJson, result.completeness()));
        profiles.save(profile);

        app.transitionTo(ApplicationStatus.EXTRACTED);
        auditTrail.save(new ApplicationEvent(app.getId(), "EXTRACTED", json(Map.of(
                "skills_count", result.skillsCount(),
                "total_experience_months", result.totalExperienceMonths(),
                "completeness", result.completeness()))));

        // Fan-out point: matcher (Ph5), dedup+fraud (Ph6) and embedder (Ph7)
        // will ALL consume this one event — the board's "three checks in parallel"
        outbox.append(Topics.RESUME_EXTRACTED, app.getId(), app.getJobId(), Map.of(
                "profile_id", profile.getId().toString(),
                "candidate_id", app.getCandidateId().toString(),
                "skills_count", result.skillsCount(),
                "total_experience_months", result.totalExperienceMonths(),
                "completeness", result.completeness()));
    }

    /** DLQ bookkeeping: audit the failure. Status stays PARSED — see class note below. */
    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        if (!processed.markProcessed(event.eventId(), "extractor-dlq")) {
            return;
        }
        // Design decision (logged in PROGRESS): the spec's state machine has no
        // EXTRACT_FAILED status. The application stays PARSED with an audit
        // event — so replaying the DLQ message later can still rescue it.
        auditTrail.save(new ApplicationEvent(event.applicationId(), "EXTRACT_FAILED",
                json(Map.of("error", errorSummary))));
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize audit details", e);
        }
    }
}
