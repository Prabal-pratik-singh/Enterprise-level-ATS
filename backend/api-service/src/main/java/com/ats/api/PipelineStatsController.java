package com.ats.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregates for the Live Flow page.
 * - current: how many applications sit in each status RIGHT NOW (occupancy)
 * - cumulative: how many ever reached each stage (from the audit trail, so a
 *   candidate now SCORED still counts in PARSED's "ever passed" number)
 * Two GROUP BY queries — stays cheap even at 500K rows.
 */
@RestController
@RequestMapping("/api/stats")
public class PipelineStatsController {

    private final JdbcTemplate jdbc;

    public PipelineStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/pipeline")
    public Map<String, Object> pipeline(@RequestParam(required = false) UUID jobId) {
        Object[] args = jobId == null ? new Object[]{} : new Object[]{jobId};

        Map<String, Long> current = new LinkedHashMap<>();
        jdbc.query(
                "select status, count(*) from applications"
                        + (jobId == null ? "" : " where job_id = ?")
                        + " group by status",
                rs -> { current.put(rs.getString(1), rs.getLong(2)); },
                args);

        Map<String, Long> cumulative = new LinkedHashMap<>();
        jdbc.query(
                // Phase 6 records two stages under its own event names: a confirmed-fraud rejection
                // is FRAUD_CONFIRMED, a flag is TRUST_CHECKED with flagged=true — count them as stages
                "select case when e.event_type = 'FRAUD_CONFIRMED' then 'REJECTED'"
                        + " when e.event_type = 'TRUST_CHECKED' then 'FLAGGED_FOR_REVIEW'"
                        + " else e.event_type end as stage, count(distinct e.application_id)"
                        + " from application_events e"
                        + " where (e.event_type in ('APPLIED','PARSED','EXTRACTED','SCORED',"
                        + "'SHORTLISTED','REJECTED','PARSE_FAILED','FRAUD_CONFIRMED')"
                        + " or (e.event_type = 'TRUST_CHECKED' and (e.details->>'flagged')::boolean))"
                        + (jobId == null ? "" : " and e.application_id in (select id from applications where job_id = ?)")
                        + " group by 1",
                rs -> { cumulative.put(rs.getString(1), rs.getLong(2)); },
                args);

        long total = current.values().stream().mapToLong(Long::longValue).sum();
        return Map.of("total", total, "current", current, "cumulative", cumulative);
    }
}
