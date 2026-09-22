package com.ats.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All LLM settings, bound from application.yml / env vars. Only services that
 * define app.llm.* in their yml (extractor now, matcher in Phase 8) get an
 * LlmClient — see the @ConditionalOnProperty in LlmConfig.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        String provider,           // ollama | groq | gemini  (env: LLM_PROVIDER)
        int connectTimeoutSeconds, // spec: 5s to establish the connection
        int readTimeoutSeconds,    // spec: 60s default; local CPU Ollama gets 180 via env
        Ollama ollama,
        Groq groq,
        Gemini gemini) {

    public record Ollama(String baseUrl, String model) {
    }

    public record Groq(String apiKey, String model) {
    }

    public record Gemini(String apiKey, String model) {
    }
}
