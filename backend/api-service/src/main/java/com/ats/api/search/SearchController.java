package com.ats.api.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ats.embedding.EmbeddingClient;
import com.ats.search.OpenSearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hybrid candidate search for one job: keyword (BM25) + meaning (kNN), fused with RRF.
 * Example: GET /api/jobs/{id}/search?q=event driven backend&skills=kafka&minExp=24
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final String NOT_RUNNING = "search is not running — docker compose --profile search up -d";

    private final ObjectMapper mapper;
    // Optional: if app.search / app.embedding aren't configured the API still starts —
    // search then answers 503 (no OpenSearch) or keyword-only (no embeddings)
    private final Optional<OpenSearchClient> search;
    private final Optional<EmbeddingClient> embeddings;

    public SearchController(ObjectMapper mapper, Optional<OpenSearchClient> search,
                            Optional<EmbeddingClient> embeddings) {
        this.mapper = mapper;
        this.search = search;
        this.embeddings = embeddings;
    }

    @GetMapping
    public Map<String, Object> search(@PathVariable UUID jobId,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(required = false) String skills,     // "kafka,java"
                                      @RequestParam(required = false) Integer minExp,    // months
                                      @RequestParam(defaultValue = "false") boolean hideFlagged,
                                      @RequestParam(defaultValue = "20") int size) {
        OpenSearchClient os = search.orElseThrow(
                () -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_RUNNING));
        int limit = Math.max(1, Math.min(size, SearchQueries.DEPTH));
        String query = q.trim();
        ArrayNode filters = SearchQueries.filters(mapper, jobId, parseSkills(skills), minExp, hideFlagged);

        if (query.isEmpty()) {   // no text: just the filters, best score first
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode hit : hits(os, SearchQueries.browse(mapper, filters))) {
                if (results.size() == limit) {
                    break;
                }
                results.add(result(hit.path("_source"), "filter", null, null, null));
            }
            return response(query, "n/a", results);
        }

        JsonNode keywordHits = hits(os, SearchQueries.keyword(mapper, query, filters));
        JsonNode semanticHits = null;
        String semanticState = "ok";
        if (embeddings.isEmpty()) {
            semanticState = "unavailable";
        } else {
            try {
                float[] vector = embeddings.get().embedQuery(query);   // ~0.1s
                semanticHits = hits(os, SearchQueries.semantic(mapper, vector, filters));
            } catch (ResponseStatusException e) {
                throw e;   // OpenSearch itself is down — keyword search would fail too
            } catch (Exception e) {
                log.warn("semantic search unavailable, answering keyword-only: {}", e.getMessage());
                semanticState = "unavailable";   // Ollama down → keyword-only, and the answer says so
            }
        }

        Map<String, JsonNode> sources = new HashMap<>();   // id → the doc fields to show
        List<String> keywordIds = ids(keywordHits, sources);
        List<String> semanticIds = semanticHits == null ? List.of() : ids(semanticHits, sources);

        List<Map<String, Object>> results = HybridRanker.fuse(keywordIds, semanticIds).stream()
                .limit(limit)
                .map(f -> result(sources.get(f.id()), f.source(), f.score(), f.keywordRank(), f.semanticRank()))
                .toList();
        return response(query, semanticState, results);
    }

    private JsonNode hits(OpenSearchClient os, JsonNode body) {
        try {
            return os.search(body).path("hits").path("hits");
        } catch (Exception e) {
            log.warn("OpenSearch query failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_RUNNING);
        }
    }

    private static List<String> ids(JsonNode hits, Map<String, JsonNode> sources) {
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : hits) {
            ids.add(hit.path("_id").asText());
            sources.putIfAbsent(hit.path("_id").asText(), hit.path("_source"));
        }
        return ids;
    }

    private Map<String, Object> result(JsonNode doc, String source, Double rrf, Integer keywordRank,
                                       Integer semanticRank) {
        Map<String, Object> row = new LinkedHashMap<>();   // keeps a stable, readable field order
        row.put("applicationId", doc.path("application_id").asText());
        row.put("name", doc.path("name").asText());
        row.put("email", doc.path("email").asText());
        row.put("status", doc.path("status").asText());
        row.put("finalScore", number(doc.path("final_score")));
        row.put("fraudScore", number(doc.path("fraud_score")));
        row.put("skills", mapper.convertValue(doc.path("skills"), List.class));
        row.put("totalExperienceMonths", doc.path("total_experience_months").isNumber()
                ? doc.path("total_experience_months").asInt() : null);
        row.put("seniority", doc.path("seniority").asText(null));
        row.put("source", source);              // "keyword" | "semantic" | "both" | "filter"
        row.put("rrfScore", rrf == null ? null : Math.round(rrf * 10000) / 10000.0);
        row.put("keywordRank", keywordRank);
        row.put("semanticRank", semanticRank);
        return row;
    }

    private static Map<String, Object> response(String q, String semantic, List<Map<String, Object>> results) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", q);
        response.put("semantic", semantic);     // "ok" | "unavailable" | "n/a" (no text query)
        response.put("count", results.size());
        response.put("results", results);
        return response;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    /** "Kafka, java,," → ["kafka", "java"] — skills are stored lower-case. */
    static List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        return Arrays.stream(skills.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
