package com.ats.matcher;

import java.time.YearMonth;

import com.ats.domain.Application;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.CandidateProfile;
import com.ats.domain.CandidateProfileRepository;
import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Matching Service front door: resume.extracted in, application.scored out.
 * Everything it needs (profile, job requirements) lives in Postgres — no MinIO,
 * no LLM — so scoring is fast, deterministic and cheap: the "hard filters +
 * weighted scoring" part of the 500K funnel.
 *
 * Note: resume.extracted is a FAN-OUT topic. We consume with group "matcher-service";
 * dedup, fraud and embedder will consume the same events later with their own groups.
 */
@Component
public class ResumeExtractedConsumer {

    static final String CONSUMER_NAME = "matcher";

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final ApplicationRepository applications;
    private final JobRepository jobs;
    private final CandidateProfileRepository profiles;
    private final SkillTaxonomy taxonomy;
    private final ScoringEngine engine;
    private final ScoreRecorder recorder;

    public ResumeExtractedConsumer(ObjectMapper mapper, ProcessedEvents processed,
                                   ApplicationRepository applications, JobRepository jobs,
                                   CandidateProfileRepository profiles, SkillTaxonomy taxonomy,
                                   ScoringEngine engine, ScoreRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.applications = applications;
        this.jobs = jobs;
        this.profiles = profiles;
        this.taxonomy = taxonomy;
        this.engine = engine;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.RESUME_EXTRACTED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));
        Job job = jobs.findById(app.getJobId())
                .orElseThrow(() -> new IllegalStateException("job not found: " + app.getJobId()));
        CandidateProfile profile = profiles.findByApplicationId(app.getId())
                .orElseThrow(() -> new IllegalStateException("profile not found for application " + app.getId()));

        JobRequirements requirements = JobRequirements.parse(mapper.readTree(job.getRequirements()), taxonomy);
        ScoreCard card = engine.score(mapper.readTree(profile.getProfile()), requirements, YearMonth.now());

        recorder.recordScore(event, card);
        log.info("scored application {}: {} (knockedOut={}, cap={})",
                event.applicationId(), card.finalScore(), card.knockedOut(), card.mustHaveCap());
    }
}
