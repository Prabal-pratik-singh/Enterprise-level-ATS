package com.ats.embedding;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    /** One shared client — services without app.embedding.base-url (parser, matcher...) get none. */
    @Bean
    @ConditionalOnProperty(prefix = "app.embedding", name = "base-url")
    public EmbeddingClient embeddingClient(EmbeddingProperties props, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
        return new EmbeddingClient(http, mapper, props);
    }
}
