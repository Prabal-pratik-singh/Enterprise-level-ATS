package com.ats.fraud;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Gathers everything one trust check needs — profile and email (Postgres), the
 * hidden-text fingerprint (parsed.json in MinIO), dedup findings (duplicate_matches)
 * — runs every check and records the verdict. Shared by both listeners:
 * resume.extracted (first check) and application.deduped (re-check with duplicates).
 */
@Service
public class TrustEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TrustEvaluator.class);

    private final ObjectMapper mapper;
    private final ApplicationRepository applications;
    private final CandidateRepository candidates;
    private final CandidateProfileRepository profiles;
    private final ResumeRepository resumes;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final TrustScorer scorer;
    private final TrustRecorder recorder;
    private final JdbcTemplate jdbc;

    public TrustEvaluator(ObjectMapper mapper,
                          ApplicationRepository applications, CandidateRepository candidates,
                          CandidateProfileRepository profiles, ResumeRepository resumes,
                          S3Client s3, S3Properties s3Props,
                          TrustScorer scorer, TrustRecorder recorder, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.applications = applications;
        this.candidates = candidates;
        this.profiles = profiles;
        this.resumes = resumes;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.scorer = scorer;
        this.recorder = recorder;
        this.jdbc = jdbc;
    }

    public void evaluate(EventEnvelope event) throws Exception {
        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));
        Candidate candidate = candidates.findById(app.getCandidateId())
                .orElseThrow(() -> new IllegalStateException("candidate not found: " + app.getCandidateId()));
        CandidateProfile profile = profiles.findByApplicationId(app.getId())
                .orElseThrow(() -> new IllegalStateException("profile not found for application " + app.getId()));

        TrustInput input = new TrustInput(mapper.readTree(profile.getProfile()),
                candidate.getEmail(), layoutOf(app), YearMonth.now(), duplicatesOf(app));
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

    /** What the Dedup Service found for this application (empty until dedup has run). */
    private List<TrustInput.DuplicateMatch> duplicatesOf(Application app) {
        return jdbc.query("select kind, similarity, explanation from duplicate_matches where application_id = ?",
                (rs, i) -> new TrustInput.DuplicateMatch(rs.getString("kind"), rs.getDouble("similarity"),
                        rs.getString("explanation")),
                app.getId());
    }
}
