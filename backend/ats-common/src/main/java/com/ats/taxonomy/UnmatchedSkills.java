package com.ats.taxonomy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Records skills the taxonomy doesn't know — an upsert that counts repeats. */
@Component
public class UnmatchedSkills {

    private final JdbcTemplate jdbc;

    public UnmatchedSkills(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String rawSkill) {
        // First sighting inserts; repeats bump the counter — classic upsert
        jdbc.update("""
                insert into unmatched_skills (raw_skill) values (?)
                on conflict (raw_skill)
                do update set occurrences = unmatched_skills.occurrences + 1, last_seen_at = now()
                """, rawSkill);
    }
}
