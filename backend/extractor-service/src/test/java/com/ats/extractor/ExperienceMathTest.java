package com.ats.extractor;

import java.time.YearMonth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceMathTest {

    private final ExperienceMath math = new ExperienceMath();
    private final ObjectMapper mapper = new ObjectMapper();
    private final YearMonth now = YearMonth.of(2026, 9); // pinned "today" keeps tests deterministic

    private com.fasterxml.jackson.databind.JsonNode exp(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void overlappingJobsCountOnce() throws Exception {
        // 2020-01..2021-12 and 2021-01..2022-12 overlap through 2021:
        // merged span = 2020-01..2022-12 = 36 months (a naive sum says 48)
        var experience = exp("""
                [{"start_date":"2020-01","end_date":"2021-12"},
                 {"start_date":"2021-01","end_date":"2022-12"}]""");
        assertThat(math.totalExperienceMonths(experience, now)).isEqualTo(36);
    }

    @Test
    void gapsAreNotCounted() throws Exception {
        // 6 months + 6 months with a gap between = 12, not 18
        var experience = exp("""
                [{"start_date":"2020-01","end_date":"2020-06"},
                 {"start_date":"2021-01","end_date":"2021-06"}]""");
        assertThat(math.totalExperienceMonths(experience, now)).isEqualTo(12);
    }

    @Test
    void currentRoleCountsUntilToday() throws Exception {
        // 2026-01..now(2026-09) inclusive = 9 months
        var experience = exp("""
                [{"start_date":"2026-01","end_date":null,"current":true}]""");
        assertThat(math.totalExperienceMonths(experience, now)).isEqualTo(9);
    }

    @Test
    void unparseableAndNonsenseRolesAreSkippedNotFatal() throws Exception {
        var experience = exp("""
                [{"start_date":"garbage","end_date":"2021-01"},
                 {"start_date":"2022-05","end_date":"2020-01"},
                 {"start_date":"2024-01","end_date":"2024-12"}]""");
        // only the sane third entry counts: 12 months
        assertThat(math.totalExperienceMonths(experience, now)).isEqualTo(12);
    }

    @Test
    void futureClaimsAreClampedToToday() throws Exception {
        var experience = exp("""
                [{"start_date":"2026-01","end_date":"2030-12"}]""");
        assertThat(math.totalExperienceMonths(experience, now)).isEqualTo(9); // 2026-01..2026-09
    }

    @Test
    void seniorityBuckets() {
        assertThat(math.seniority(12)).isEqualTo("junior");
        assertThat(math.seniority(36)).isEqualTo("mid");
        assertThat(math.seniority(90)).isEqualTo("senior");
        assertThat(math.seniority(200)).isEqualTo("lead");
    }

    @Test
    void completenessRewardsFilledSections() throws Exception {
        var full = mapper.readTree("""
                {"contact":{"email":"a@b.com","phone":"+91-9"},"summary":"text",
                 "experience":[{}],"education":[{}],"skills":["a","b","c"],
                 "projects":[{}],"certifications":["x"]}""");
        assertThat(math.completeness(full)).isEqualTo(1.0);

        var empty = mapper.readTree("{}");
        assertThat(math.completeness(empty)).isEqualTo(0.0);
    }
}
