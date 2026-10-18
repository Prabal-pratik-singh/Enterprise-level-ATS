package com.ats.extractor;

import java.util.ArrayList;
import java.util.List;

import com.ats.llm.LlmClient;
import com.ats.taxonomy.SkillTaxonomy;
import com.ats.taxonomy.UnmatchedSkills;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Load-test extraction on the synthetic layout — and proof real resumes never take this path. */
class RuleExtractorTest {

    // Rohan Bose's resume exactly as PDFBox parsed it (2026-10-17)
    static final String SYNTHETIC = """
            Rohan Bose
            rohan.bose9351@gmail.com  |  +91-9040669154  |  Pune, India
            SUMMARY
            Software Developer with 10 years of experience building jenkins and docker systems. Comfortable owning
            services end to end, from data model to deployment.
            SKILLS
            jenkins, docker, microservices, django, kubernetes, java, mongodb, git, postgresql
            EXPERIENCE
            Software Developer — CloudSprint  (Jul 2024 - Jan 2026, Full-time)
            - Designed event-driven data flows with java and microservices, cutting batch latency by 40%.
            - Built and operated django-based microservices handling 120K requests/day, backed by microservices.
            Senior Software Engineer — Finlytics  (Apr 2022 - May 2024, Full-time)
            - Owned on-call for java services; wrote runbooks and dashboards with mongodb.
            Java Developer — CloudSprint  (Mar 2019 - Apr 2022, Full-time)
            - Built and operated django-based microservices handling 400K requests/day, backed by docker.
            Senior Software Engineer — ZenStack  (Jun 2016 - Present, Full-time)
            - Implemented CI/CD pipelines with kubernetes; containerized services using postgresql.
            EDUCATION
            B.E. Information Technology, BITS Pilani — graduated 2016
            PROJECTS
            Payment Reconciliation Service
            - Matched ledger entries across providers using postgresql, persisted in jenkins.
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleExtractor rules = new RuleExtractor(mapper);

    @Test
    void theSyntheticLayoutBecomesAFullProfile() {
        ObjectNode p = rules.extract(SYNTHETIC);
        assertThat(p.at("/contact/name").asText()).isEqualTo("Rohan Bose");
        assertThat(p.at("/contact/email").asText()).isEqualTo("rohan.bose9351@gmail.com");
        assertThat(p.at("/contact/phone").asText()).isEqualTo("+91-9040669154");
        assertThat(p.at("/contact/location").asText()).isEqualTo("Pune, India");
        assertThat(p.path("skills")).hasSize(9);
        assertThat(p.path("summary").asText()).startsWith("Software Developer with 10 years").endsWith("deployment.");

        assertThat(p.path("experience")).hasSize(4);
        assertThat(p.at("/experience/0/title").asText()).isEqualTo("Software Developer");
        assertThat(p.at("/experience/0/company").asText()).isEqualTo("CloudSprint");
        assertThat(p.at("/experience/0/start_date").asText()).isEqualTo("2024-07");
        assertThat(p.at("/experience/0/end_date").asText()).isEqualTo("2026-01");
        assertThat(p.at("/experience/0/description").asText()).contains("event-driven data flows").contains("120K");
        assertThat(p.at("/experience/3/current").asBoolean()).isTrue();      // "Present"
        assertThat(p.at("/experience/3/end_date").isNull()).isTrue();

        assertThat(p.at("/education/0/year").asInt()).isEqualTo(2016);
        assertThat(p.at("/education/0/institution").asText()).isEqualTo("BITS Pilani");
        assertThat(p.at("/projects/0/name").asText()).isEqualTo("Payment Reconciliation Service");
        assertThat(p.at("/projects/0/description").asText()).startsWith("Matched ledger entries");
    }

    @Test
    void theRuleProfilePassesTheSameSchemaAsAnLlmAnswer() {
        assertThat(new ProfileValidator(mapper).validate(rules.extract(SYNTHETIC))).isEmpty();
    }

    @Test
    void aRealLookingResumeIsRefusedNotGuessed() {
        String real = """
                PRABAL PRATIK SINGH
                Bengaluru · prabal@example.com
                Technical Skills: Java, Spring Boot, React
                Work History
                Intern, Acme Labs (2024)
                """;
        assertThatThrownBy(() -> rules.extract(real)).hasMessageContaining("not the synthetic layout");
    }

    @Test
    void monthsReadAsIsoYearMonth() {
        assertThat(RuleExtractor.yearMonth("Jul 2024")).isEqualTo("2024-07");
        assertThat(RuleExtractor.yearMonth("Dec 2019")).isEqualTo("2019-12");
        assertThat(RuleExtractor.yearMonth("Present")).isNull();
    }

    // ---- routing: who reads what ----

    static class CountingLlm implements LlmClient {
        final List<String> calls = new ArrayList<>();

        @Override
        public String complete(String system, String user, String schema) {
            calls.add("llm");
            return """
                    {"contact": {"name": "Real Person", "email": "real@example.com", "phone": null, "location": null},
                     "experience": [], "education": [], "skills": ["java"]}""";
        }
    }

    private ProfileExtractor extractor(LlmClient llm) {
        UnmatchedSkills ignore = new UnmatchedSkills(null) {
            @Override
            public void record(String rawSkill) {   // no database in a unit test
            }
        };
        return new ProfileExtractor(llm, new ProfileValidator(mapper), new SkillTaxonomy(mapper), ignore,
                new ExperienceMath(), mapper, rules);
    }

    @Test
    void aLoadTestResumeNeverCallsTheLlm() {
        CountingLlm llm = new CountingLlm();
        var result = extractor(llm).extract(SYNTHETIC, true);
        assertThat(llm.calls).isEmpty();
        assertThat(result.method()).isEqualTo("rules");
        assertThat(result.totalExperienceMonths()).isPositive();
    }

    @Test
    void aRealResumeAlwaysGoesToTheLlmEvenIfItLooksSynthetic() {
        CountingLlm llm = new CountingLlm();
        var result = extractor(llm).extract(SYNTHETIC, false);   // same text, but NOT marked load test
        assertThat(llm.calls).containsExactly("llm");
        assertThat(result.method()).isEqualTo("llm");
    }

    @Test
    void aLoadTestResumeThatRulesCannotReadFailsInsteadOfUsingTheLlm() {
        CountingLlm llm = new CountingLlm();
        assertThatThrownBy(() -> extractor(llm).extract("Some Person\nsome@x.com\nno headings here", true))
                .hasMessageContaining("not the synthetic layout");
        assertThat(llm.calls).isEmpty();   // the daily quota is protected
    }
}
