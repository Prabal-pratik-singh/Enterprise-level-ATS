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
                "select e.event_type, count(distinct e.application_id) from application_events e"
                        + " where e.event_type in ('APPLIED','PARSED','EXTRACTED','SCORED',"
                        + "'FLAGGED_FOR_REVIEW','SHORTLISTED','REJECTED','PARSE_FAILED')"
                        + (jobId == null ? "" : " and e.application_id in (select id from applications where job_id = ?)")
                        + " group by e.event_type",
                rs -> { cumulative.put(rs.getString(1), rs.getLong(2)); },
                args);

        long total = current.values().stream().mapToLong(Long::longValue).sum();
        return Map.of("total", total, "current", current, "cumulative", cumulative);
    }
}
