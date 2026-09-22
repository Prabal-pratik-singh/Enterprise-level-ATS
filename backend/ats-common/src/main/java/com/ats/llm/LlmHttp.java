package com.ats.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One place for "POST JSON, get JSON back, fail loudly" — used by all three clients. */
final class LlmHttp {

    static JsonNode postJson(HttpClient http, ObjectMapper mapper, String url,
                             JsonNode body, Map<String, String> headers, Duration readTimeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(readTimeout) // spec's read timeout: give up if the model hangs
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(request::header); // provider-specific auth headers

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { // anything not 2xx is a failure
                // Include a snippet of the body — that's where providers explain themselves
                throw new IllegalStateException("LLM call failed: HTTP " + response.statusCode()
                        + " from " + url + " — " + snippet(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) { // timeouts, connection refused, bad JSON...
            // Escaping exceptions ride the consumer's retry → DLQ machinery.
            throw new IllegalStateException("LLM call failed: " + url, e);
        }
    }

    private static String snippet(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 300));
    }

    private LlmHttp() {
    }
}
