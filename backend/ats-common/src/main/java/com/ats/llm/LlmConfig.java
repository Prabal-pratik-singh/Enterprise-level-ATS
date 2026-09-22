package com.ats.llm;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    /**
     * Builds exactly ONE client, chosen by LLM_PROVIDER. The condition below
     * means: services whose yml has no app.llm block (api, parser) simply get
     * no LlmClient bean at all — they don't talk to models.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = "provider")
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds())) // spec: 5s
                .build();
        Duration readTimeout = Duration.ofSeconds(props.readTimeoutSeconds());

        return switch (props.provider().toLowerCase()) {
            case "ollama" -> new OllamaClient(http, mapper, props, readTimeout);
            case "groq" -> new GroqClient(http, mapper, props, readTimeout);
            case "gemini" -> new GeminiClient(http, mapper, props, readTimeout);
            default -> throw new IllegalStateException("unknown LLM_PROVIDER: " + props.provider());
        };
    }
}
