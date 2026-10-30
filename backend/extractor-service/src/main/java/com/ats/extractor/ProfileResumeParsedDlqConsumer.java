package com.ats.extractor;

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
 * A profile resume the extractor gave up on (4 tries): mark it EXTRACT_FAILED with a plain sentence for
 * the candidate. The technical cause (attached by the recoverer as a header) is only logged.
 */
@Component
public class ProfileResumeParsedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProfileResumeParsedDlqConsumer.class);

    private final ObjectMapper mapper;
    private final ProfileExtractRecorder recorder;

    public ProfileResumeParsedDlqConsumer(ObjectMapper mapper, ProfileExtractRecorder recorder) {
        this.mapper = mapper;
        this.recorder = recorder;
    }

    @KafkaListener(topics = "profile.resume.parsed.dlq", groupId = "extractor-profile-dlq")
    public void handle(ConsumerRecord<String, String> record) {
        // Never throw from here — there is no .dlq.dlq. Log and move on.
        try {
            EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
            recorder.recordFailure(event);
            log.warn("profile resume of account {} marked EXTRACT_FAILED: {}", event.subjectId(),
                    header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
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
