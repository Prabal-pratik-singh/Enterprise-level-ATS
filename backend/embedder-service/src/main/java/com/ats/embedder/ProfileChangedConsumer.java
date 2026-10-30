package com.ats.embedder;

import java.time.YearMonth;
import java.util.Set;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.embedding.EmbeddingClient;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.ats.profile.EffectiveProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Part 2: a candidate's profile changed (a resume was read, or they fixed something) → embed the
 * EFFECTIVE profile — what was read plus their own fixes — so the vector says what the page says.
 * It always re-reads the latest state, so two quick edits both end up embedding the newest profile.
 * Failure reasons (parse_failed / extract_failed) only exist to update an open page: skipped here.
 */
@Component
public class ProfileChangedConsumer {

    static final String CONSUMER_NAME = "embedder-profile";   // processed_events ledger name
    static final Set<String> EMBED_REASONS = Set.of("extracted", "edited");

    private static final Logger log = LoggerFactory.getLogger(ProfileChangedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final MasterProfileRepository profiles;
    private final EffectiveProfile effectiveProfile;
    private final EmbeddingClient embeddings;
    private final ProfileEmbeddingRecorder recorder;

    public ProfileChangedConsumer(ObjectMapper mapper, ProcessedEvents processed, MasterProfileRepository profiles,
                                  EffectiveProfile effectiveProfile, EmbeddingClient embeddings,
                                  ProfileEmbeddingRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.profiles = profiles;
        this.effectiveProfile = effectiveProfile;
        this.embeddings = embeddings;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.PROFILE_CHANGED, groupId = "embedder-profile")
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        String reason = event.data() == null ? "" : event.data().path("reason").asText("");
        if (!EMBED_REASONS.contains(reason)) {
            return;   // a failure notice for the page — nothing new to embed
        }
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }
        MasterProfile profile = profiles.findById(event.subjectId()).orElse(null);
        if (profile == null || profile.getProfile() == null) {
            log.warn("profile.changed for account {} but no extracted profile — nothing to embed", event.subjectId());
            return;
        }
        JsonNode effective = effectiveProfile.apply(mapper.readTree(profile.getProfile()),
                mapper.readTree(profile.getProfileOverrides()), YearMonth.now());
        String text = ProfileText.of(effective);
        float[] vector = embeddings.embedDocument(text);   // ~0.3 s on CPU — outside the DB transaction
        recorder.record(event, profile.getUserId(), reason, vector, text.length());
        log.info("embedded the profile of account {} ({}; {} chars → {} dims)",
                profile.getUserId(), reason, text.length(), vector.length);
    }
}
