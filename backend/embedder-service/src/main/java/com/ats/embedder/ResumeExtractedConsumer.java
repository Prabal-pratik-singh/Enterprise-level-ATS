package com.ats.embedder;

import com.ats.domain.CandidateProfile;
import com.ats.domain.CandidateProfileRepository;
import com.ats.embedding.EmbeddingClient;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Embedder front door: resume.extracted in (its own group "embedder-service" — the
 * fourth reader of this fan-out topic), resume.embedded out.
 */
@Component
public class ResumeExtractedConsumer {

    static final String CONSUMER_NAME = "embedder";

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final CandidateProfileRepository profiles;
    private final EmbeddingClient embeddings;
    private final EmbeddingRecorder recorder;

    public ResumeExtractedConsumer(ObjectMapper mapper, ProcessedEvents processed,
                                   CandidateProfileRepository profiles, EmbeddingClient embeddings,
                                   EmbeddingRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.RESUME_EXTRACTED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        CandidateProfile profile = profiles.findByApplicationId(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("profile not found for " + event.applicationId()));
        String text = ProfileText.of(mapper.readTree(profile.getProfile()));
        float[] vector = embeddings.embedDocument(text);   // ~0.3s on CPU — outside the DB transaction

        recorder.record(event, profile, vector, text.length());
        log.info("embedded application {} ({} chars → {} dims)", event.applicationId(), text.length(), vector.length);
    }
}
