package com.ats.parser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ats.config.S3Properties;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Part 2: the parser's second front door — a resume a candidate uploaded to their OWN profile.
 * Same reading code as an application's resume (DocumentParser), its own consumer group so its
 * progress is tracked apart from Part 1's, and parsed.json lands next to the file:
 *   profiles/{account}/v1/resume.pdf → profiles/{account}/v1/parsed.json
 * Exceptions escape on purpose: 4 tries, then profile.resume.uploaded.dlq.
 */
@Component
public class ProfileResumeUploadedConsumer {

    static final String CONSUMER_NAME = "parser-profile";   // processed_events ledger name

    private static final Logger log = LoggerFactory.getLogger(ProfileResumeUploadedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final ProfileResumeRepository resumes;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final DocumentParser parser;
    private final ProfileParseRecorder recorder;

    public ProfileResumeUploadedConsumer(ObjectMapper mapper, ProcessedEvents processed,
                                         ProfileResumeRepository resumes, S3Client s3, S3Properties s3Props,
                                         DocumentParser parser, ProfileParseRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.resumes = resumes;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.parser = parser;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.PROFILE_RESUME_UPLOADED, groupId = "parser-profile")
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        UUID resumeId = UUID.fromString(event.dataText("profile_resume_id"));
        ProfileResume resume = resumes.findById(resumeId)
                .orElseThrow(() -> new IllegalStateException("profile resume not found: " + resumeId));

        byte[] fileBytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(s3Props.bucket()).key(resume.getS3Key()).build()).asByteArray();
        ParsedDocument doc = parser.parse(fileBytes);

        // Written BEFORE the DB transaction: a retry simply overwrites the same JSON
        String parsedKey = resume.getS3Key().substring(0, resume.getS3Key().lastIndexOf('/') + 1) + "parsed.json";
        s3.putObject(
                PutObjectRequest.builder().bucket(s3Props.bucket()).key(parsedKey)
                        .contentType("application/json").build(),
                RequestBody.fromBytes(parsedJson(resume, doc)));

        recorder.recordSuccess(event, resume, doc, parsedKey);
        log.info("parsed profile resume v{} of account {} via {} ({} chars)",
                resume.getVersion(), resume.getUserId(), doc.parseMethod(), doc.text().length());
    }

    /** The same shape the application parsed.json has, with the account instead of an application. */
    private byte[] parsedJson(ProfileResume resume, ParsedDocument doc) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", resume.getUserId());
        body.put("profile_resume_id", resume.getId());
        body.put("source_s3_key", resume.getS3Key());
        body.put("parse_method", doc.parseMethod());
        body.put("page_count", doc.pageCount());
        body.put("ocr_confidence", doc.ocrConfidence());
        body.put("layout", doc.layout());
        body.put("text", doc.text());
        body.put("parsed_at", Instant.now().toString());
        return mapper.writeValueAsBytes(body);
    }
}
