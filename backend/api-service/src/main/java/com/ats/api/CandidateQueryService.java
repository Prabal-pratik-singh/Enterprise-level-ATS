package com.ats.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Read model for the dashboard: one JOIN across applications, candidates,
 * scores and profiles, ranked by final score in the database. Plain SQL via
 * JdbcTemplate — a read query this shaped doesn't need JPA ceremony.
 */
@Service
public class CandidateQueryService {

    /** One row of the ranked table (also the SSE push payload). */
    public record CandidateRow(UUID applicationId, String name, String email, String status,
                               Double finalScore, JsonNode components, Double fraudScore,
                               JsonNode flags, Integer totalExperienceMonths, String seniority,
                               Double completeness, boolean knockedOut) {
    }

    private static final String BASE_SELECT = """
            select a.id as application_id, c.full_name, c.email, a.status,
                   s.final_score, s.components, s.fraud_score, s.flags, s.evidence,
                   p.completeness,
                   (p.profile->'derived'->>'total_experience_months')::int as total_months,
                   p.profile->'derived'->>'seniority' as seniority,
                   coalesce((s.evidence)::text like '%knockout%', false) as knocked_out
            from applications a
            join candidates c on c.id = a.candidate_id
            left join scores s on s.application_id = a.id
            left join candidate_profiles p on p.application_id = a.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CandidateQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Ranked page for one job: best score first, unscored candidates last. */
    public List<CandidateRow> pageForJob(UUID jobId, int page, int size) {
        return jdbc.query(BASE_SELECT + """
                        where a.job_id = ?
                        order by s.final_score desc nulls last, a.created_at asc
                        limit ? offset ?
                        """,
                rowMapper(), jobId, size, page * size);
    }

    public long countForJob(UUID jobId) {
        Long n = jdbc.queryForObject("select count(*) from applications where job_id = ?", Long.class, jobId);
        return n == null ? 0 : n;
    }

    /** Single row — used for the SSE push when application.scored arrives. */
    public Optional<CandidateRow> byApplicationId(UUID applicationId) {
        List<CandidateRow> rows = jdbc.query(BASE_SELECT + " where a.id = ?", rowMapper(), applicationId);
        return rows.stream().findFirst();
    }

    private RowMapper<CandidateRow> rowMapper() {
        return (rs, i) -> new CandidateRow(
                UUID.fromString(rs.getString("application_id")),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("status"),
                (Double) rs.getObject("final_score"),
                parseJson(rs.getString("components")),
                (Double) rs.getObject("fraud_score"),
                parseJson(rs.getString("flags")),
                (Integer) rs.getObject("total_months"),
                rs.getString("seniority"),
                (Double) rs.getObject("completeness"),
                rs.getBoolean("knocked_out"));
    }

    private JsonNode parseJson(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (Exception e) {
            return null; // a malformed stored blob must not break the whole list
        }
    }
}
