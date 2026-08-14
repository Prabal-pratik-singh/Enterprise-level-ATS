package com.ats.config;

import java.util.stream.Stream;

import com.ats.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaTopicConfig {

    // 12 partitions per topic: enough parallelism to demo `--scale parser=8`.
    // Replication 1 — single broker in the demo.
    static final int PARTITIONS = 12;
    static final int REPLICAS = 1;

    @Bean
    public KafkaAdmin.NewTopics atsTopics() {
        NewTopic[] topics = Stream.concat(
                        Topics.ALL.stream(),
                        Topics.ALL.stream().map(Topics::dlq))
                .map(name -> TopicBuilder.name(name)
                        .partitions(PARTITIONS)
                        .replicas(REPLICAS)
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
