package com.ats.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Text → 768 numbers that capture its MEANING (Ollama + nomic-embed-text).
 * Texts about similar things get similar numbers even with different words
 * ("Kafka pipelines" ≈ "event driven backend").
 *
 * nomic was trained with two prefixes — documents and queries must be labelled
 * differently or similarity quietly degrades — so callers pick a method, never a prefix.
 */
public class EmbeddingClient {

    public static final int DIMENSIONS = 768;   // must match vector(768) in Postgres and the OpenSearch mapping

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final EmbeddingProperties props;

    public EmbeddingClient(HttpClient http, ObjectMapper mapper, EmbeddingProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.props = props;
    }

    /** For a resume being stored. */
    public float[] embedDocument(String text) {
        return embed("search_document: " + text);
    }

    /** For what a recruiter typed into the search box. */
    public float[] embedQuery(String text) {
        return embed("search_query: " + text);
    }

    public String model() {
        return props.model();
    }

    private float[] embed(String input) {
        ObjectNode body = mapper.createObjectNode().put("model", props.model());
        body.putArray("input").add(input);
        // Ollama unloads an idle model after 5 min; reloading took 4.1s (measured) and made the first
        // search after a break silently keyword-only. Keep this small model (274 MB) loaded instead.
        body.put("keep_alive", props.keepAlive());
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(props.baseUrl() + "/api/embed"))
                            .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))  // spec: 10s for non-LLM calls
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding failed: HTTP " + response.statusCode()
                        + " — " + snippet(response.body()));
            }
            return toVector(mapper.readTree(response.body()).path("embeddings").path(0));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {   // timeout, connection refused → Kafka retry → DLQ
            throw new IllegalStateException("embedding call failed: " + props.baseUrl(), e);
        }
    }

    /** Ollama's JSON array → float[]; a different model (other size) must fail loudly, not be stored. */
    static float[] toVector(JsonNode array) {
        if (array.size() != DIMENSIONS) {
            throw new IllegalStateException("expected " + DIMENSIONS + " dimensions, got " + array.size());
        }
        float[] out = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            out[i] = (float) array.get(i).asDouble();
        }
        return out;
    }

    /** pgvector's text format: "[0.12,-0.5,...]" — written with ?::vector, no extra driver needed. */
    public static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String snippet(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 300));
    }
}
