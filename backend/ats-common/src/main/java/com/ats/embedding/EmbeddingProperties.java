package com.ats.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding settings (app.embedding.*). Only services that set app.embedding.base-url
 * (the embedder now, the api for search queries) get an EmbeddingClient bean.
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        String baseUrl,             // Ollama, e.g. http://ollama:11434 (env OLLAMA_BASE_URL)
        String model,               // nomic-embed-text → 768 numbers per text
        int connectTimeoutSeconds,  // spec: 5s to connect
        int readTimeoutSeconds,     // spec: 10s for non-LLM calls (one embedding takes ~0.3s)
        String keepAlive) {         // how long Ollama keeps the model loaded after a call, e.g. "24h"
}
