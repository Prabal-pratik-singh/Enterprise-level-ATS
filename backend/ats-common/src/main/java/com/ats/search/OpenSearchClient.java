package com.ats.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A deliberately small OpenSearch client over plain HTTP + JSON: bulk-write and search
 * are the only two things this project needs, so no extra client library.
 */
public class OpenSearchClient {

    public static final String INDEX = "candidates";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final SearchProperties props;

    public OpenSearchClient(HttpClient http, ObjectMapper mapper, SearchProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.props = props;
    }

    /** Upsert many documents in ONE request. id = application id, so re-indexing replaces, never duplicates. */
    public void bulkIndex(List<ObjectNode> docs) {
        JsonNode response = post("/_bulk", bulkBody(docs), "application/x-ndjson");
        if (response.path("errors").asBoolean()) {   // _bulk returns 200 even when items fail — check per item
            throw new IllegalStateException("bulk index had failures: " + firstError(response));
        }
    }

    /** Run a search body, return OpenSearch's raw answer (the API builds the queries). */
    public JsonNode search(JsonNode body) {
        return post("/" + INDEX + "/_search", body.toString(), "application/json");
    }

    /** _bulk speaks "newline-delimited JSON": one action line, then the document, per document. */
    static String bulkBody(List<ObjectNode> docs) {
        StringBuilder ndjson = new StringBuilder();
        for (ObjectNode doc : docs) {
            ndjson.append("{\"index\":{\"_index\":\"").append(INDEX)
                  .append("\",\"_id\":\"").append(doc.path("application_id").asText()).append("\"}}\n")
                  .append(doc).append('\n');   // the body MUST end with a newline
        }
        return ndjson.toString();
    }

    private JsonNode post(String path, String body, String contentType) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(props.baseUrl() + path))
                            .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenSearch " + path + " failed: HTTP " + response.statusCode()
                        + " — " + snippet(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {   // timeout, connection refused → Kafka retry → DLQ
            throw new IllegalStateException("OpenSearch call failed: " + props.baseUrl() + path, e);
        }
    }

    /** The first failed item's reason — enough for a human to see what went wrong. */
    private static String firstError(JsonNode response) {
        for (JsonNode item : response.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode()) {
                return error.path("type").asText() + ": " + error.path("reason").asText();
            }
        }
        return "unknown";
    }

    private static String snippet(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 300));
    }
}
