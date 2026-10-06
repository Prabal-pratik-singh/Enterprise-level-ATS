package com.ats.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.ats.events.Topics;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Queue depths for the Live Flow page — and Phase 8's lag demo widget.
 * lag = (latest offset − committed offset) summed over a topic's partitions:
 * literally "how many events this worker group hasn't processed yet".
 */
@RestController
@RequestMapping("/api/admin")
public class LagController {

    /** The worker queues in pipeline order: group -> the topic it consumes. */
    private static final Map<String, String> GROUP_TO_TOPIC = new LinkedHashMap<>();
    static {
        GROUP_TO_TOPIC.put("parser-service", Topics.RESUME_UPLOADED);
        GROUP_TO_TOPIC.put("extractor-service", Topics.RESUME_PARSED);
        GROUP_TO_TOPIC.put("matcher-service", Topics.RESUME_EXTRACTED);
    }

    private final AdminClient admin;

    public LagController(KafkaAdmin kafkaAdmin) {
        // One long-lived AdminClient: cheap to keep, wasteful to rebuild per poll
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    public record QueueLag(String group, String topic, long lag) {
    }

    @GetMapping("/lag")
    public Map<String, Object> lag() {
        List<QueueLag> queues = new ArrayList<>();
        long total = 0;
        try {
            for (var entry : GROUP_TO_TOPIC.entrySet()) {
                String group = entry.getKey();
                String topic = entry.getValue();

                Map<TopicPartition, OffsetAndMetadata> committed = admin
                        .listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);

                // A group that never committed (worker not started yet) has lag
                // equal to EVERYTHING in the topic — ask the topic for its partitions.
                List<TopicPartition> partitions;
                if (committed.isEmpty()) {
                    var description = admin.describeTopics(List.of(topic))
                            .allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
                    partitions = description.partitions().stream()
                            .map(p -> new TopicPartition(topic, p.partition())).toList();
                } else {
                    partitions = committed.keySet().stream()
                            .filter(tp -> tp.topic().equals(topic)).toList();
                }

                Map<TopicPartition, OffsetSpec> query = new HashMap<>();
                partitions.forEach(tp -> query.put(tp, OffsetSpec.latest()));
                var latest = admin.listOffsets(query).all().get(5, TimeUnit.SECONDS);

                long lag = 0;
                for (var e : latest.entrySet()) {
                    OffsetAndMetadata c = committed.get(e.getKey());
                    lag += Math.max(0, e.getValue().offset() - (c == null ? 0 : c.offset()));
                }
                queues.add(new QueueLag(group, topic, lag));
                total += lag;
            }
            return Map.of("queues", queues, "totalLag", total);
        } catch (Exception e) {
            // a Kafka hiccup must not 500 the dashboard — report "unknown" instead
            return Map.of("queues", queues, "totalLag", -1,
                    "error", e.getMessage() == null ? "lag unavailable" : e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
