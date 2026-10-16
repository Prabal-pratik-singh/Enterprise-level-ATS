package com.ats.indexer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ats.events.EventEnvelope;
import com.ats.events.Topics;
import com.ats.search.OpenSearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the OpenSearch read model in step with Postgres. A BATCH listener: Kafka hands
 * over up to 500 events, waiting at most 2s to fill the batch (max-poll-records=500,
 * fetch-max-wait=2s in application.yml) — the spec's "batch up to 500 or 2s window".
 *
 * Idempotent by design: every event just means "re-read this application from Postgres
 * and overwrite its document", so duplicates and out-of-order events are harmless —
 * no processed_events ledger needed.
 */
@Component
public class ChangeEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventsConsumer.class);

    private final ObjectMapper mapper;
    private final CandidateDocuments documents;
    private final OpenSearchClient search;

    public ChangeEventsConsumer(ObjectMapper mapper, CandidateDocuments documents, OpenSearchClient search) {
        this.mapper = mapper;
        this.documents = documents;
        this.search = search;
    }

    @KafkaListener(topics = {Topics.APPLICATION_SCORED, Topics.RESUME_EMBEDDED,
                             Topics.APPLICATION_FLAGGED, Topics.SHORTLIST_UPDATED},
                   batch = "true")
    public void handle(List<String> messages) throws Exception {
        Set<UUID> changed = new LinkedHashSet<>();   // 30 events about 20 applications → 20 documents
        for (String message : messages) {
            changed.add(mapper.readValue(message, EventEnvelope.class).applicationId());
        }
        List<ObjectNode> docs = documents.load(changed);
        if (!docs.isEmpty()) {
            search.bulkIndex(docs);
        }
        log.info("indexed {} document(s) from {} event(s)", docs.size(), messages.size());
    }
}
