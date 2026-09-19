package com.ats.parser;

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
 * Bookkeeping for poison messages. The retry policy already moved the record
 * to resume.uploaded.dlq (with the exception in kafka_dlt-* headers); this
 * listener records the business consequence: resume FAILED, application
 * PARSE_FAILED, audit row with the error. The DLQ message itself stays in the
 * topic for humans to inspect in Kafka UI — we only read it, never remove it.
 */
@Component
public class ResumeUploadedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadedDlqConsumer.class);

    private final ObjectMapper mapper;
    private final ParseRecorder recorder;

    public ResumeUploadedDlqConsumer(ObjectMapper mapper, ParseRecorder recorder) {
        this.mapper = mapper;
        this.recorder = recorder;
    }

    // @KafkaListener needs a compile-time constant, so ".dlq" is spelled out.
    // Own group id: this listener's progress is tracked separately from the main consumer's.
    @KafkaListener(topics = "resume.uploaded.dlq", groupId = "parser-service-dlq")
    public void handle(ConsumerRecord<String, String> record) {
        // Never throw from here — an exception would send this record to
        // "resume.uploaded.dlq.dlq", which doesn't exist. Log and move on.
        try {
            EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
            String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE); // attached by the recoverer
            recorder.recordFailure(event, error == null ? "unknown parser failure" : error);
            log.warn("application {} marked PARSE_FAILED: {}", event.applicationId(), error);
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
