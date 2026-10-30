package com.ats.profile;

import java.time.YearMonth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the AI read + the candidate's own fixes = the one profile every part of the system uses. */
class EffectiveProfileTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EffectiveProfile effective = new EffectiveProfile(new ExperienceMath());
    private final YearMonth now = YearMonth.of(2026, 10);   // pinned "today"

    /** Sneha's shape: two roles, 27 + 29 = 56 months, a gap in between. */
    private ObjectNode sneha() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"skills": ["spring-boot", "microservices", "redis", "html"],
                 "experience": [
                   {"company": "Hyperloop Retail", "title": "Software Engineer",
                    "start_date": "2021-07", "end_date": "2023-09", "current": false},
                   {"company": "CloudSprint", "title": "Senior Software Engineer",
                    "start_date": "2019-01", "end_date": "2021-05", "current": false}],
                 "contact": {"email": "sneha@test.dev", "phone": "+91 90000 00000"},
                 "summary": "Backend engineer", "education": [{"degree": "B.Tech"}],
                 "derived": {"total_experience_months": 56, "seniority": "mid", "completeness": 0.75}}""");
    }

    private JsonNode overrides(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void skillsAreWhatWasReadMinusRemovedPlusAdded() throws Exception {
        JsonNode result = effective.apply(sneha(),
                overrides("{\"skills_added\": [\"kafka\"], \"skills_removed\": [\"html\"]}"), now);

        assertThat(result.path("skills").toString()).isEqualTo("[\"spring-boot\",\"microservices\",\"redis\",\"kafka\"]");
    }

    @Test
    void aDateFixIsFoundByCompanyAndTitleAndTheMonthsFollow() throws Exception {
        JsonNode result = effective.apply(sneha(),
                overrides("{\"experience\": {\"hyperloop retail|software engineer\": {\"end_date\": \"2024-06\"}}}"), now);

        assertThat(result.at("/experience/0/end_date").asText()).isEqualTo("2024-06");
        assertThat(result.at("/derived/total_experience_months").asInt()).isEqualTo(65);   // 36 + 29, was 56
        assertThat(result.at("/derived/seniority").asText()).isEqualTo("senior");          // over 60 months now
    }

    @Test
    void aFixSurvivesAReParseThatListsTheRolesInAnotherOrder() throws Exception {
        ObjectNode reordered = sneha();
        ArrayNode roles = (ArrayNode) reordered.get("experience");
        JsonNode first = roles.remove(0);
        roles.add(first);   // Hyperloop is now second

        JsonNode result = effective.apply(reordered,
                overrides("{\"experience\": {\"hyperloop retail|software engineer\": {\"end_date\": \"2024-06\"}}}"), now);

        assertThat(result.at("/experience/1/end_date").asText()).isEqualTo("2024-06");
        assertThat(result.at("/derived/total_experience_months").asInt()).isEqualTo(65);
    }

    @Test
    void presentMeansStillWorkingThere() throws Exception {
        JsonNode result = effective.apply(sneha(),
                overrides("{\"experience\": {\"hyperloop retail|software engineer\": {\"end_date\": \"present\"}}}"), now);

        assertThat(result.at("/experience/0/current").asBoolean()).isTrue();
        assertThat(result.at("/experience/0/end_date").isNull()).isTrue();
        assertThat(result.at("/derived/total_experience_months").asInt()).isEqualTo(93);   // Jul 2021..Oct 2026 = 64, + 29
    }

    @Test
    void withoutFixesTheDerivedFactsAreRecomputedFromTheSameDates() throws Exception {
        JsonNode result = effective.apply(sneha(), overrides("{}"), now);

        assertThat(result.at("/derived/total_experience_months").asInt()).isEqualTo(56);
        assertThat(result.path("skills").size()).isEqualTo(4);
    }

    @Test
    void theStoredProfileItselfIsNeverChanged() throws Exception {
        ObjectNode extracted = sneha();
        String before = extracted.toString();

        effective.apply(extracted, overrides("{\"skills_added\": [\"kafka\"], "
                + "\"experience\": {\"hyperloop retail|software engineer\": {\"end_date\": \"2024-06\"}}}"), now);

        assertThat(extracted.toString()).isEqualTo(before);
    }

    @Test
    void roleKeysIgnoreCaseAndOuterSpaces() {
        assertThat(EffectiveProfile.roleKey(" Hyperloop Retail ", "Software Engineer"))
                .isEqualTo("hyperloop retail|software engineer");
    }
}
