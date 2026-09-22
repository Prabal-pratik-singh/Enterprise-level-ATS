package com.ats.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Local Ollama (default provider): the model runs on this machine — free and
 * private, but CPU-slow (~30-90s per resume). Cloud swap = env change.
 */
public class OllamaClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties.Ollama config;
    private final Duration readTimeout;

    public OllamaClient(HttpClient http, ObjectMapper mapper, LlmProperties props, Duration readTimeout) {
        this.http = http;
        this.mapper = mapper;
        this.config = props.ollama();
        this.readTimeout = readTimeout;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String jsonSchema) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());   // qwen2.5:3b by default
        body.put("stream", false);           // one complete answer, not a token stream
        body.put("format", "json");          // Ollama's JSON mode: output MUST parse as JSON
        body.putObject("options").put("temperature", 0); // 0 = deterministic, no creativity wanted

        ArrayNode messages = body.putArray("messages");
        // The schema rides inside the system prompt so the model knows the target shape
        messages.addObject().put("role", "system")
                .put("content", systemPrompt + "\n\nYour answer MUST be a single JSON object matching this schema:\n" + jsonSchema);
        messages.addObject().put("role", "user").put("content", userPrompt);

        JsonNode response = LlmHttp.postJson(http, mapper, config.baseUrl() + "/api/chat",
                body, Map.of(), readTimeout);
        // Ollama's answer shape: { "message": { "role": "assistant", "content": "..." } }
        return response.path("message").path("content").asText();
    }
}
