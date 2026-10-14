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
    private final TrustEvaluator evaluator;

    public ResumeExtractedConsumer(ObjectMapper mapper, ProcessedEvents processed, TrustEvaluator evaluator) {
        this.mapper = mapper;
        this.processed = processed;
        this.evaluator = evaluator;
    }

    @KafkaListener(topics = Topics.RESUME_EXTRACTED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }
        evaluator.evaluate(event);
    }
}
