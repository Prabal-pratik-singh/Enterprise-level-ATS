package com.ats.api.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The job's top candidates, turned into compact cards for the LLM. */
@Component
public class RerankCards {

    static final int MAX_EVIDENCE = 8;      // the matcher's evidence chips — the "why" behind the formula
    static final int MAX_DESCRIPTION = 220; // per role: enough for depth signals, not the whole resume

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RerankCards(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Best match first. Only SCORED/SHORTLISTED and not knocked out — flagged/rejected are left alone. */
    public List<RerankCard> topForJob(UUID jobId, int limit) {
        return jdbc.query("""
                select a.id, c.full_name, s.match_score, s.evidence::text as evidence, p.profile::text as profile
                from applications a
                join candidates c on c.id = a.candidate_id
                join scores s on s.application_id = a.id
                join candidate_profiles p on p.application_id = a.id
                where a.job_id = ? and a.status in ('SCORED', 'SHORTLISTED')
                  and s.match_score is not null
                  and coalesce(s.evidence::text like '%knockout%', false) = false
                order by s.final_score desc nulls last, a.created_at
                limit ?""",
                (rs, i) -> new RerankCard(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getDouble("match_score"), card(rs.getString("profile"), rs.getString("evidence"),
                                rs.getDouble("match_score"))),
                jobId, limit);
    }

    ObjectNode card(String profileJson, String evidenceJson, double matchScore) {
        ObjectNode card = mapper.createObjectNode();
        try {
            JsonNode p = mapper.readTree(profileJson == null ? "{}" : profileJson);
            card.put("formula_score", Math.round(matchScore * 10) / 10.0);
            card.put("experience_months", p.path("derived").path("total_experience_months").asInt(0));
            card.put("seniority", p.path("derived").path("seniority").asText(""));
            card.set("skills", p.path("skills"));
            ArrayNode roles = card.putArray("roles");
            for (JsonNode job : p.path("experience")) {
                String d = job.path("description").asText("");
                roles.addObject()
                        .put("role", job.path("title").asText("") + " at " + job.path("company").asText(""))
                        .put("from", job.path("start_date").asText(""))
                        .put("to", job.path("current").asBoolean(false) ? "present" : job.path("end_date").asText(""))
                        .put("what", d.length() > MAX_DESCRIPTION ? d.substring(0, MAX_DESCRIPTION) + "…" : d);
            }
            ArrayNode projects = card.putArray("projects");
            p.path("projects").forEach(pr -> projects.add(pr.path("name").asText("")));
            ArrayNode evidence = card.putArray("matcher_evidence");
            List<String> texts = new ArrayList<>();
            mapper.readTree(evidenceJson == null ? "[]" : evidenceJson).forEach(e -> texts.add(e.path("text").asText()));
            texts.stream().limit(MAX_EVIDENCE).forEach(evidence::add);
        } catch (Exception e) {
            card.put("note", "profile unreadable");
        }
        return card;
    }
}
