package com.ats.fraud;

import java.io.IOException;
import java.time.YearMonth;

import com.ats.config.S3Properties;
import com.ats.domain.Application;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.Candidate;
import com.ats.domain.CandidateProfile;
import com.ats.domain.CandidateProfileRepository;
import com.ats.domain.CandidateRepository;
import com.ats.domain.Resume;
import com.ats.domain.ResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Trust Service front door: resume.extracted in, a fraud verdict out.
 * Same fan-out event the matcher consumes — but with its OWN consumer group
 * ("fraud-service"), so each service gets its own copy and its own offsets.
 */
@Component
public class ResumeExtractedConsumer {

    static final String CONSUMER_NAME = "fraud";

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final ApplicationRepository applications;
    private final CandidateRepository candidates;
    private final CandidateProfileRepository profiles;
    private final ResumeRepository resumes;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final TrustScorer scorer;
    private final TrustRecorder recorder;

    public ResumeExtractedConsumer(ObjectMapper mapper, ProcessedEvents processed,
                                   ApplicationRepository applications, CandidateRepository candidates,
                                   CandidateProfileRepository profiles, ResumeRepository resumes,
                                   S3Client s3, S3Properties s3Props,
                                   TrustScorer scorer, TrustRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.applications = applications;
        this.candidates = candidates;
        this.profiles = profiles;
        this.resumes = resumes;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.scorer = scorer;
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
        Candidate candidate = candidates.findById(app.getCandidateId())
                .orElseThrow(() -> new IllegalStateException("candidate not found: " + app.getCandidateId()));
        CandidateProfile profile = profiles.findByApplicationId(app.getId())
                .orElseThrow(() -> new IllegalStateException("profile not found for application " + app.getId()));

        TrustInput input = new TrustInput(mapper.readTree(profile.getProfile()),
                candidate.getEmail(), layoutOf(app), YearMonth.now());
        TrustScorer.TrustVerdict verdict = scorer.evaluate(input);

        recorder.recordVerdict(event, verdict);
        log.info("trust-checked application {}: fraud_score={} flagged={} flags={}", event.applicationId(),
                verdict.fraudScore(), verdict.flagged(), verdict.flags().stream().map(FraudFlag::code).toList());
    }

    /**
     * The parser's font/ink fingerprint, read from parsed.json of the NEWEST resume
     * version (the event doesn't carry the key; the resumes table does).
     * null = no fingerprint (DOCX / image resumes) — HiddenTextCheck then stays silent.
     */
    private TrustInput.HiddenTextStats layoutOf(Application app) throws IOException {
        int latest = resumes.maxVersion(app.getId());
        String parsedKey = resumes.findByApplicationIdAndVersion(app.getId(), latest)
                .map(Resume::getParsedS3Key).orElse(null);
        if (parsedKey == null) {
            return null;
        }
        byte[] parsedJson = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(s3Props.bucket()).key(parsedKey).build()).asByteArray();
        JsonNode layout = mapper.readTree(parsedJson).path("layout");
        if (!layout.isObject()) {
            return null;
        }
        return new TrustInput.HiddenTextStats(layout.path("totalChars").asInt(), layout.path("tinyFontChars").asInt(),
                layout.path("nearWhiteChars").asInt(), layout.path("hiddenTextSample").asText(""));
    }
}
