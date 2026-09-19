package com.ats.config;

import com.ats.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    /**
     * Global consumer error policy, applied to every @KafkaListener:
     * 1 original try + 3 retries, 1 second apart. Still failing? The record is
     * published to "<topic>.dlq" — same partition number, original payload,
     * and the exception class/message/stacktrace attached as kafka_dlt-* headers
     * (that's the "error context" a human needs when inspecting the DLQ).
     * The consumer then moves on — a poison message never blocks its partition.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // The "recoverer" is the last resort: it decides WHERE a hopeless record goes.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                // (failed record, exception) -> destination. We keep the same partition
                // number so ordering context survives (DLQ topics also have 12 partitions).
                (record, ex) -> new TopicPartition(Topics.dlq(record.topic()), record.partition()));

        // FixedBackOff(interval=1000ms, maxRetries=3): 4 attempts total, then the recoverer runs.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
