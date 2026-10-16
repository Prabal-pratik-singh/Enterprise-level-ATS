package com.ats.api.search;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The OpenSearch query bodies. Keyword and semantic share ONE filter list, so they never disagree. */
final class SearchQueries {

    static final int DEPTH = 50;   // how far down each list we look before fusing
    // what keyword search reads, and how much each field counts (skills matter most)
    static final List<String> FIELDS = List.of("skills_text^3", "experience^2", "summary", "projects");
    // ≤3 words: every word must match; longer: 75% of them. Measured: "any word" matched 44 of 53
    // resumes for "event driven backend" (everyone says "backend"), which made the labels meaningless.
    static final String MINIMUM_MATCH = "3<75%";
    // never ship 768 numbers per result back to the browser
    static final List<String> SOURCE = List.of("application_id", "name", "email", "status", "final_score",
            "fraud_score", "skills", "total_experience_months", "seniority");

    static ArrayNode filters(ObjectMapper m, UUID jobId, List<String> skills, Integer minExp, boolean hideFlagged) {
        ArrayNode filters = m.createArrayNode();
        filters.addObject().putObject("term").put("job_id", jobId.toString());
        for (String skill : skills) {   // one filter per skill → the candidate must have ALL of them
            filters.addObject().putObject("term").put("skills", skill);
        }
        if (minExp != null) {
            filters.addObject().putObject("range").putObject("total_experience_months").put("gte", minExp);
        }
        if (hideFlagged) {
            filters.addObject().putObject("bool").putObject("must_not")
                   .putObject("term").put("status", "FLAGGED_FOR_REVIEW");
        }
        return filters;
    }

    static ObjectNode keyword(ObjectMapper m, String q, ArrayNode filters) {
        ObjectNode body = base(m);
        ObjectNode bool = body.putObject("query").putObject("bool");
        ObjectNode multiMatch = bool.putObject("must").putObject("multi_match");
        multiMatch.put("query", q);
        FIELDS.forEach(multiMatch.putArray("fields")::add);
        multiMatch.put("minimum_should_match", MINIMUM_MATCH);
        bool.set("filter", filters);
        return body;
    }

    static ObjectNode semantic(ObjectMapper m, float[] vector, ArrayNode filters) {
        ObjectNode body = base(m);
        ObjectNode knn = body.putObject("query").putObject("knn").putObject("embedding");
        ArrayNode v = knn.putArray("vector");
        for (float x : vector) {
            v.add(x);
        }
        knn.put("k", DEPTH);
        // the Lucene engine applies this filter DURING the nearest-neighbour search, not after
        knn.putObject("filter").putObject("bool").set("filter", filters);
        return body;
    }

    /** Empty q: just the filters, best final score first. */
    static ObjectNode browse(ObjectMapper m, ArrayNode filters) {
        ObjectNode body = base(m);
        body.putObject("query").putObject("bool").set("filter", filters);
        body.putArray("sort").addObject().putObject("final_score").put("order", "desc").put("missing", "_last");
        return body;
    }

    private static ObjectNode base(ObjectMapper m) {
        ObjectNode body = m.createObjectNode().put("size", DEPTH);
        SOURCE.forEach(body.putArray("_source")::add);
        return body;
    }

    private SearchQueries() {
    }
}
