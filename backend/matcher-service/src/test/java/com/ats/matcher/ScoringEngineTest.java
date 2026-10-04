package com.ats.matcher;

import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec-mandated tests: must-have gate, fresher weight shift, recency decay. */
class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();
    private final ObjectMapper mapper = new ObjectMapper();
    private final YearMonth now = YearMonth.of(2026, 10); // pinned "today"

    // Requirements built directly (no taxonomy needed — names already canonical)
    private JobRequirements backendJob() {
        return new JobRequirements(List.of("java", "spring-boot", "kafka", "sql"),
                List.of("docker", "aws", "react"), 24, false, JobRequirements.DEFAULT_WEIGHTS);
    }

    private JobRequirements fresherJob() {
        return new JobRequirements(List.of("java", "sql"),
                List.of("spring-boot", "git"), 0, true, JobRequirements.FRESHER_WEIGHTS);
    }

    private JsonNode profile(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void missingMustHavesCapTheFinalScore() throws Exception {
        // Candidate A: spectacular resume but missing kafka AND sql (2 of 4 must-haves)
        var missingMusts = profile("""
                {"skills":["java","spring-boot","docker","aws","react"],
                 "experience":[{"title":"Senior Engineer","company":"X","start_date":"2020-01","current":true,
                               "description":"java spring-boot docker aws systems"}],
                 "education":[{"degree":"B.Tech","institution":"IIT","year":2019}],
                 "projects":[{"name":"Platform","description":"java spring-boot aws docker react"}],
                 "certifications":["AWS Certified","CKA"],
                 "derived":{"total_experience_months":60,"completeness":1.0}}""");
        // Candidate B: has ALL four must-haves, otherwise modest
        var allMusts = profile("""
                {"skills":["java","spring-boot","kafka","sql"],
                 "experience":[{"title":"Engineer","company":"Y","start_date":"2023-01","current":true,
                               "description":"java spring-boot kafka sql services"}],
                 "education":[{"degree":"B.Tech","institution":"NIT","year":2022}],
                 "projects":[],"certifications":[],
                 "derived":{"total_experience_months":36,"completeness":0.7}}""");

        ScoreCard capped = engine.score(missingMusts, backendJob(), now);
        ScoreCard complete = engine.score(allMusts, backendJob(), now);

        assertThat(capped.mustHaveCap()).isEqualTo(0.5);            // 2 missing x 0.25 off the ceiling
        assertThat(capped.finalScore()).isLessThanOrEqualTo(50.0);  // the gate is a hard ceiling
        // The whole point: complete-but-modest OUTRANKS spectacular-but-missing-musts
        assertThat(complete.finalScore()).isGreaterThan(capped.finalScore());
    }

    @Test
    void fresherWeightShiftLetsProjectsBeatExperience() throws Exception {
        // Zero experience, strong projects + education — the classic good fresher
        var fresher = profile("""
                {"skills":["java","sql","spring-boot","git"],
                 "experience":[],
                 "education":[{"degree":"B.Tech CSE","institution":"VIT","year":2026}],
                 "projects":[{"name":"Campus ERP","description":"java sql spring-boot git backend"}],
                 "certifications":["Oracle Java"],
                 "derived":{"total_experience_months":0,"completeness":0.9}}""");

        ScoreCard onFresherJob = engine.score(fresher, fresherJob(), now);
        ScoreCard onStandardJob = engine.score(fresher, backendJob(), now);

        // On the standard job (24-month bar): knocked out and crushed
        assertThat(onStandardJob.knockedOut()).isTrue();
        assertThat(onStandardJob.finalScore()).isLessThanOrEqualTo(15.0);
        // On the fresher job: no knockout, experience barely matters, projects carry
        assertThat(onFresherJob.knockedOut()).isFalse();
        assertThat(onFresherJob.finalScore()).isGreaterThan(onStandardJob.finalScore() + 15);
    }

    @Test
    void recencyDecayPrefersCurrentSkillUseOverStale() throws Exception {
        JobRequirements javaJob = new JobRequirements(List.of("java"), List.of(), 0, false,
                JobRequirements.DEFAULT_WEIGHTS);
        var currentUse = profile("""
                {"skills":["java"],
                 "experience":[{"title":"Dev","company":"A","start_date":"2024-01","current":true,
                               "description":"building java services"}],
                 "education":[],"projects":[],"certifications":[],
                 "derived":{"total_experience_months":30,"completeness":0.5}}""");
        var staleUse = profile("""
                {"skills":["java"],
                 "experience":[{"title":"Dev","company":"B","start_date":"2016-01","end_date":"2018-06",
                               "current":false,"description":"building java services"}],
                 "education":[],"projects":[],"certifications":[],
                 "derived":{"total_experience_months":30,"completeness":0.5}}""");

        ScoreCard current = engine.score(currentUse, javaJob, now);
        ScoreCard stale = engine.score(staleUse, javaJob, now);

        // current use => recency 1.0; last used 2018 (8y ago) => 0.4
        assertThat(current.components().get("skill_match")).isGreaterThan(stale.components().get("skill_match"));
        assertThat(current.finalScore()).isGreaterThan(stale.finalScore());
    }

    @Test
    void wayBelowExperienceBarIsKnockedOutButStillVisible() throws Exception {
        var tooJunior = profile("""
                {"skills":["java","spring-boot","kafka","sql"],
                 "experience":[{"title":"Intern","company":"Z","start_date":"2026-01","current":true,
                               "description":"java kafka work"}],
                 "education":[{"degree":"B.Tech","year":2025}],"projects":[],"certifications":[],
                 "derived":{"total_experience_months":10,"completeness":0.8}}""");

        ScoreCard card = engine.score(tooJunior, backendJob(), now); // 10 < 24/2

        assertThat(card.knockedOut()).isTrue();
        assertThat(card.finalScore()).isLessThanOrEqualTo(15.0); // crushed, not hidden
        assertThat(card.evidence()).anyMatch(e -> e.type().equals("knockout"));
    }

    @Test
    void heavyOverqualificationDampensExperienceFit() throws Exception {
        var veteran = profile("""
                {"skills":["java","spring-boot","kafka","sql"],
                 "experience":[{"title":"Principal","company":"W","start_date":"2016-01","current":true,
                               "description":"java spring-boot kafka sql"}],
                 "education":[{"degree":"M.Tech","year":2010}],"projects":[],"certifications":[],
                 "derived":{"total_experience_months":80,"completeness":0.9}}""");

        ScoreCard card = engine.score(veteran, backendJob(), now); // 80/24 = 3.3x

        assertThat(card.components().get("experience_fit")).isEqualTo(0.8);
        assertThat(card.evidence()).anyMatch(e -> e.text().contains("overqualified"));
    }
}
