package com.ats.matcher;

import java.nio.charset.StandardCharsets;

import com.ats.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Bookkeeper for scoring poison messages. Records WHY in the audit trail; the
 * application stays EXTRACTED (replayable). Never throws — no dlq-of-dlq loops.
 */
@Component
public class ResumeExtractedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedDlqConsumer.class);

    private final ObjectMapper mapper;
    private final ScoreRecorder recorder;

    public ResumeExtractedDlqConsumer(ObjectMapper mapper, ScoreRecorder recorder) {
        this.mapper = mapper;
        this.recorder = recorder;
    }

    @KafkaListener(topics = "resume.extracted.dlq", groupId = "matcher-service-dlq")
    public void handle(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
            String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            recorder.recordFailure(event, error == null ? "unknown scoring failure" : error);
            log.warn("scoring failed for application {} (status stays EXTRACTED): {}",
                    event.applicationId(), error);
        } catch (Exception e) {
            log.error("could not process DLQ record at offset {} — leaving it for manual inspection",
                    record.offset(), e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
