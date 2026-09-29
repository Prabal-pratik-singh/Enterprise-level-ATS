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
 * The extractor's front door: resume.parsed arrives → fetch parsed.json from
 * MinIO → hand the text to ProfileExtractor (LLM + validation + math) → let
 * ExtractRecorder commit everything in one transaction. Exceptions escape on
 * purpose: retry → resume.parsed.dlq.
 */
@Component
public class ResumeParsedConsumer {

    static final String CONSUMER_NAME = "extractor"; // our processed_events identity

    private static final Logger log = LoggerFactory.getLogger(ResumeParsedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final ProfileExtractor extractor;
    private final ExtractRecorder recorder;

    public ResumeParsedConsumer(ObjectMapper mapper, ProcessedEvents processed, S3Client s3,
                                S3Properties s3Props, ProfileExtractor extractor, ExtractRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.extractor = extractor;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.RESUME_PARSED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);

        // Skip duplicates BEFORE burning 60s of LLM time on them
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        // parsed.json was written by the parser right next to the resume
        String parsedKey = event.dataText("parsed_s3_key");
        byte[] parsedJson = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(s3Props.bucket()).key(parsedKey).build()).asByteArray();
        String resumeText = mapper.readTree(parsedJson).path("text").asText("");
        if (resumeText.isBlank()) {
            // Nothing to extract from — fail loudly so it lands in the DLQ for a human
            throw new IllegalStateException("parsed.json has no text for application " + event.applicationId());
        }

        ProfileExtractor.ExtractedProfile result = extractor.extract(resumeText); // the slow, smart part

        recorder.recordSuccess(event, result);
        log.info("extracted application {}: {} skills, {} months experience, completeness {}",
                event.applicationId(), result.skillsCount(), result.totalExperienceMonths(), result.completeness());
    }
}
