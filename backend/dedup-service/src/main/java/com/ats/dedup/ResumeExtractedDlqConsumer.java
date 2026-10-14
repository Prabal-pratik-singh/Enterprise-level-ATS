package com.ats.dedup;

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
 * Bookkeeper for dedup poison messages. Records WHY in the audit trail; the
 * application's status is left alone. Never throws — no dlq-of-dlq loops.
 */
@Component
public class ResumeExtractedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedDlqConsumer.class);

    private final ObjectMapper mapper;
    private final DedupRecorder recorder;

    public ResumeExtractedDlqConsumer(ObjectMapper mapper, DedupRecorder recorder) {
        this.mapper = mapper;
        this.recorder = recorder;
    }

    @KafkaListener(topics = "resume.extracted.dlq", groupId = "dedup-service-dlq")
    public void handle(ConsumerRecord<String, String> record) {
        try {
            // resume.extracted is fan-out, so the matcher's and fraud's failures land in this SAME dlq topic.
            // Spring stamps every dead letter with the consumer group that gave up on it.
            if (!"dedup-service".equals(header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))) {
                return; // not ours
            }
            EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
            String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            recorder.recordFailure(event, error == null ? "unknown dedup failure" : error);
            log.warn("dedup failed for application {}: {}", event.applicationId(), error);
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
