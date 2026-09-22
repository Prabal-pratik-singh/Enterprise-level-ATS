package com.ats.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Google Gemini. Needs GEMINI_API_KEY; model defaults to a fast flash variant. */
public class GeminiClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties.Gemini config;
    private final Duration readTimeout;

    public GeminiClient(HttpClient http, ObjectMapper mapper, LlmProperties props, Duration readTimeout) {
        this.http = http;
        this.mapper = mapper;
        this.config = props.gemini();
        this.readTimeout = readTimeout;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String jsonSchema) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("LLM_PROVIDER=gemini but GEMINI_API_KEY is not set");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + config.model() + ":generateContent";

        ObjectNode body = mapper.createObjectNode();
        // Gemini separates the system prompt from the conversation:
        body.putObject("system_instruction").putArray("parts").addObject()
                .put("text", systemPrompt + "\n\nYour answer MUST be a single JSON object matching this schema:\n" + jsonSchema);
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", userPrompt);
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", 0);
        generation.put("responseMimeType", "application/json"); // Gemini's JSON mode

        // Key goes in a header, not the URL — URLs end up in logs, headers don't
        JsonNode response = LlmHttp.postJson(http, mapper, url, body,
                Map.of("x-goog-api-key", config.apiKey()), readTimeout);
        // Answer shape: { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
        return response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
    }
}
