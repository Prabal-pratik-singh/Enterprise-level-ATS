package com.ats.indexer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds search documents from Postgres — the source of truth, never the event payload.
 * Whatever order events arrive in, a document always shows the application's CURRENT state.
 */
@Component
public class CandidateDocuments {

    /** One application's raw columns, as read from Postgres. */
    record Row(UUID applicationId, UUID jobId, UUID candidateId, String status, String name, String email,
               Double finalScore, Double matchScore, Double fraudScore, String profileJson, String embedding) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CandidateDocuments(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** ONE query for the whole batch (500 events → at most 500 ids, never 500 queries). */
    public List<ObjectNode> load(Collection<UUID> applicationIds) {
        List<Row> rows = jdbc.query("""
                select a.id, a.job_id, a.candidate_id, a.status, c.full_name, c.email,
                       s.final_score, s.match_score, s.fraud_score,
                       p.profile::text as profile, p.embedding::text as embedding
                from applications a
                join candidates c on c.id = a.candidate_id
                join candidate_profiles p on p.application_id = a.id
                left join scores s on s.application_id = a.id
                where a.id = any(?::uuid[])""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        rs.getObject("candidate_id", UUID.class), rs.getString("status"),
                        rs.getString("full_name"), rs.getString("email"),
                        dbl(rs.getObject("final_score")), dbl(rs.getObject("match_score")),
                        dbl(rs.getObject("fraud_score")), rs.getString("profile"), rs.getString("embedding")),
                (Object) applicationIds.toArray(UUID[]::new));
        List<ObjectNode> docs = new ArrayList<>();
        for (Row row : rows) {
            docs.add(toDocument(row));
        }
        return docs;
    }

    /** Row → the document shape the candidates index template expects (dynamic: strict). */
    ObjectNode toDocument(Row row) {
        JsonNode profile;
        try {
            profile = mapper.readTree(row.profileJson() == null ? "{}" : row.profileJson());
        } catch (Exception e) {
            profile = mapper.createObjectNode(); // a malformed profile still gets a findable name/status
        }
        ObjectNode doc = mapper.createObjectNode();
        doc.put("application_id", row.applicationId().toString());
        doc.put("job_id", row.jobId().toString());
        doc.put("candidate_id", row.candidateId().toString());
        doc.put("name", row.name());
        doc.put("email", row.email());
        putText(doc, "location", profile.path("contact").path("location").asText(null));
        doc.put("status", row.status());
        putNumber(doc, "final_score", row.finalScore());
        putNumber(doc, "match_score", row.matchScore());
        putNumber(doc, "fraud_score", row.fraudScore());

        ArrayNode skills = doc.putArray("skills");                 // keyword: exact filters
        List<String> skillWords = new ArrayList<>();
        profile.path("skills").forEach(skill -> {
            skills.add(skill.asText());
            skillWords.add(skill.asText());
        });
        doc.put("skills_text", String.join(" ", skillWords));      // text: keyword search over skills

        JsonNode derived = profile.path("derived");
        if (derived.path("total_experience_months").canConvertToInt()) {
            doc.put("total_experience_months", derived.path("total_experience_months").asInt());
        }
        putText(doc, "seniority", derived.path("seniority").asText(null));
        putText(doc, "summary", profile.path("summary").asText(null));

        StringBuilder experience = new StringBuilder();
        for (JsonNode job : profile.path("experience")) {
            experience.append(job.path("title").asText("")).append(" at ").append(job.path("company").asText(""))
                      .append(": ").append(job.path("description").asText("")).append('\n');
        }
        doc.put("experience", experience.toString());
        StringBuilder projects = new StringBuilder();
        for (JsonNode project : profile.path("projects")) {
            projects.append(project.path("name").asText("")).append(": ")
                    .append(project.path("description").asText("")).append('\n');
        }
        doc.put("projects", projects.toString());

        // not embedded yet → no vector field at all; kNN simply skips this document until resume.embedded
        if (row.embedding() != null) {
            doc.set("embedding", parseVector(row.embedding()));
        }
        doc.put("indexed_at", Instant.now().toString());
        return doc;
    }

    /** pgvector's text form "[0.1,-0.5,...]" → a JSON array of numbers. */
    private ArrayNode parseVector(String text) {
        ArrayNode vector = mapper.createArrayNode();
        String inner = text.substring(1, text.length() - 1);
        for (String part : inner.split(",")) {
            vector.add(Float.parseFloat(part));
        }
        return vector;
    }

    private static void putText(ObjectNode doc, String field, String value) {
        if (value != null && !value.isBlank()) {
            doc.put(field, value);
        }
    }

    private static void putNumber(ObjectNode doc, String field, Double value) {
        if (value != null) {
            doc.put(field, value);
        }
    }

    /** Postgres `numeric` arrives as BigDecimal — never cast, always convert. */
    private static Double dbl(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
