package com.ats.extractor;

import com.ats.config.S3Properties;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Part 2: the extractor's second front door — a candidate's own profile resume. Same ProfileExtractor
 * (always the LLM: this is a real person's resume, never the load-test rules), its own consumer group,
 * and the result goes to the candidate's master profile instead of an application.
 * Exceptions escape on purpose: 4 tries, then profile.resume.parsed.dlq.
 */
@Component
public class ProfileResumeParsedConsumer {

    static final String CONSUMER_NAME = "extractor-profile";   // processed_events ledger name

    private static final Logger log = LoggerFactory.getLogger(ProfileResumeParsedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final ProfileExtractor extractor;
    private final ProfileExtractRecorder recorder;

    public ProfileResumeParsedConsumer(ObjectMapper mapper, ProcessedEvents processed, S3Client s3,
                                       S3Properties s3Props, ProfileExtractor extractor,
                                       ProfileExtractRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.extractor = extractor;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.PROFILE_RESUME_PARSED, groupId = "extractor-profile")
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        // Skip duplicates BEFORE spending an LLM call on them
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }
        byte[] parsedJson = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(s3Props.bucket()).key(event.dataText("parsed_s3_key")).build()).asByteArray();
        String resumeText = mapper.readTree(parsedJson).path("text").asText("");
        if (resumeText.isBlank()) {
            throw new IllegalStateException("parsed.json has no text for profile resume "
                    + event.dataText("profile_resume_id"));
        }
        ProfileExtractor.ExtractedProfile result = extractor.extract(resumeText);   // the slow, smart part
        recorder.recordSuccess(event, result);
        log.info("extracted profile resume v{} of account {}: {} skills, {} months, completeness {}",
                event.dataText("version"), event.subjectId(), result.skillsCount(),
                result.totalExperienceMonths(), result.completeness());
    }
}
