package com.ats.indexer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
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
 * Bookkeeper for events the indexer gave up on (OpenSearch down past the retries).
 * Records an INDEX_FAILED audit row. The index is only a COPY: the real recovery is a
 * rebuild — reset the indexer group's offsets and it re-reads everything from Postgres.
 * Never throws — no dlq-of-dlq loops.
 */
@Component
public class IndexFailureConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexFailureConsumer.class);

    private final ObjectMapper mapper;
    private final ApplicationEventRepository auditTrail;

    public IndexFailureConsumer(ObjectMapper mapper, ApplicationEventRepository auditTrail) {
        this.mapper = mapper;
        this.auditTrail = auditTrail;
    }

    @KafkaListener(topics = {"application.scored.dlq", "resume.embedded.dlq",
                             "application.flagged.dlq", "shortlist.updated.dlq"},
                   groupId = "indexer-service-dlq")
    public void handle(ConsumerRecord<String, String> record) {
        try {
            // these dlq topics are shared with the api's SSE listener etc. — only handle our own
            if (!"indexer-service".equals(header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))) {
                return;
            }
            EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
            String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            // duplicates are harmless here (an extra audit line), so no idempotency ledger
            auditTrail.save(new ApplicationEvent(event.applicationId(), "INDEX_FAILED", mapper.writeValueAsString(
                    Map.of("topic", record.topic(), "error", error == null ? "unknown indexing failure" : error))));
            log.warn("indexing failed for application {}: {}", event.applicationId(), error);
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
