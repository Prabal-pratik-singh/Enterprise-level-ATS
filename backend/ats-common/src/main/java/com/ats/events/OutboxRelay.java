package com.ats.events;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.ats.domain.OutboxRepository;
import com.ats.domain.OutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo simplification: a polling relay instead of Debezium/CDC. Every backend
 * container runs one; SKIP LOCKED keeps them from double-claiming rows.
 * Delivery is at-least-once (a mid-batch failure re-sends the batch), which is
 * safe because every consumer dedupes via processed_events.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 200;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 500)
    public void relay() {
        Integer published = tx.execute(status -> {
            List<OutboxRow> rows = outbox.lockNextUnpublished(BATCH);
            for (OutboxRow row : rows) {
                try {
                    kafka.send(row.getTopic(), row.getEventKey(), row.getPayload()).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("outbox publish failed for row " + row.getId(), e);
                }
                row.markPublished();
            }
            return rows.size();
        });
        if (published != null && published > 0) {
            log.info("outbox relay published {} event(s)", published);
        }
    }
}
