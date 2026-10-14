package com.ats.fraud;

import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The second way in: the Dedup Service finished and found something. Dedup and the
 * first trust check run in PARALLEL, so whichever finishes last must see the full
 * picture — this re-runs every check, now including the duplicate findings.
 */
@Component
public class ApplicationDedupedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDedupedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final TrustEvaluator evaluator;

    public ApplicationDedupedConsumer(ObjectMapper mapper, ProcessedEvents processed, TrustEvaluator evaluator) {
        this.mapper = mapper;
        this.processed = processed;
        this.evaluator = evaluator;
    }

    @KafkaListener(topics = Topics.APPLICATION_DEDUPED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (event.data() == null || !event.data().path("has_findings").asBoolean(false)) {
            return; // nothing new — the resume.extracted check already covered it
        }
        if (processed.alreadyProcessed(event.eventId(), ResumeExtractedConsumer.CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }
        evaluator.evaluate(event); // full re-check, now including the duplicates
    }
}
