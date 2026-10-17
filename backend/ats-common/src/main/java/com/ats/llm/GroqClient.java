package com.ats.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groq's cloud API (OpenAI-compatible wire format) — answers in ~1s.
 * Needs GROQ_API_KEY in the environment; resume text leaves the machine,
 * fine for synthetic demo data.
 */
public class GroqClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties.Groq config;
    private final Duration readTimeout;
    private final String url;

    public GroqClient(HttpClient http, ObjectMapper mapper, LlmProperties props, Duration readTimeout) {
        this(http, mapper, props, readTimeout, URL);
    }

    /** Tests point this at a local fake server. */
    GroqClient(HttpClient http, ObjectMapper mapper, LlmProperties props, Duration readTimeout, String url) {
        this.http = http;
        this.mapper = mapper;
        this.config = props.groq();
        this.readTimeout = readTimeout;
        this.url = url;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String jsonSchema) {
        try {
            return call(config.model(), systemPrompt, userPrompt, jsonSchema);
        } catch (IllegalStateException e) {
            // Groq rejected the model's own output as invalid JSON: repeating the same model at
            // temperature 0 can't succeed — go straight to the stronger one (gpt-oss-120b rescued
            // every resume the 20b model failed on: 2026-09-29 and 2026-10-17)
            if (hasFallback() && e.getMessage() != null && e.getMessage().contains("json_validate_failed")) {
                log.info("{} returned invalid JSON — retrying once with {}", config.model(), config.fallbackModel());
                return call(config.fallbackModel(), systemPrompt, userPrompt, jsonSchema);
            }
            throw e;
        }
    }

    @Override
    public String completeWithStrongerModel(String systemPrompt, String userPrompt, String jsonSchema) {
        return call(hasFallback() ? config.fallbackModel() : config.model(), systemPrompt, userPrompt, jsonSchema);
    }

    private boolean hasFallback() {
        return config.fallbackModel() != null && !config.fallbackModel().isBlank()
                && !config.fallbackModel().equals(config.model());
    }

    /** One model, one answer — with 429 rate limits waited out (sleep for the server's hint, up to 6 attempts). */
    private String call(String model, String systemPrompt, String userPrompt, String jsonSchema) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("LLM_PROVIDER=groq but GROQ_API_KEY is not set");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model); // openai/gpt-oss-20b by default, the fallback model when asked
        body.put("temperature", 0);
        // OpenAI-style JSON mode: the API itself refuses to return non-JSON
        body.putObject("response_format").put("type", "json_object");

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", systemPrompt + "\n\nYour answer MUST be a single JSON object matching this schema:\n" + jsonSchema);
        messages.addObject().put("role", "user").put("content", userPrompt);

        // Free-tier Groq enforces tokens-per-minute. HTTP 429 means "wait",
        // not "fail" — so we sleep for the server's suggested time and retry,
        // up to 6 attempts, instead of letting the message die into the DLQ.
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response = LlmHttp.postJson(http, mapper, url, body,
                        Map.of("Authorization", "Bearer " + config.apiKey()), readTimeout);
                // OpenAI-style answer shape: { "choices": [ { "message": { "content": "..." } } ] }
                return response.path("choices").path(0).path("message").path("content").asText();
            } catch (IllegalStateException e) {
                boolean rateLimited = e.getMessage() != null && e.getMessage().contains("HTTP 429");
                if (!rateLimited || attempt >= 6) {
                    throw e; // real errors (or persistent throttling) still escalate
                }
                sleepSeconds(retryAfterSeconds(e.getMessage(), attempt * 5L));
            }
        }
    }

    /** Groq's 429 body says "Please try again in 4.095s" — obey it (+1s margin). */
    private static long retryAfterSeconds(String message, long fallback) {
        Matcher m = Pattern.compile("try again in ([0-9.]+)s").matcher(message);
        return m.find() ? (long) Math.ceil(Double.parseDouble(m.group(1))) + 1 : fallback;
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out a rate limit", ie);
        }
    }
}
