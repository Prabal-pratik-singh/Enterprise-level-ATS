package com.ats.api;

import com.ats.events.EventEnvelope;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * api-service's ear on the pipeline: when the matcher announces a score, fetch
 * the fresh row and push it to the job's SSE watchers. Group "api-sse" with
 * latest offsets — a live ticker must not replay history on restart. No
 * idempotency ledger either: pushing the same row twice is harmless UI noise.
 */
@Component
public class ScoredEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ScoredEventsListener.class);

    private final ObjectMapper mapper;
    private final CandidateQueryService query;
    private final SseHub hub;

    public ScoredEventsListener(ObjectMapper mapper, CandidateQueryService query, SseHub hub) {
        this.mapper = mapper;
        this.query = query;
        this.hub = hub;
    }

    @KafkaListener(topics = Topics.APPLICATION_SCORED, groupId = "api-sse",
            properties = "auto.offset.reset=latest")
    public void onScored(String message) {
        try {
            EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
            query.byApplicationId(event.applicationId())
                    .ifPresent(row -> hub.push(event.jobId(), row));
        } catch (Exception e) {
            // UI pushes must never poison the topic — log and move on
            log.warn("could not push SSE update: {}", e.getMessage());
        }
    }
}
