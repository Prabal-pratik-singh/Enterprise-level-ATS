package com.ats.api;

import java.util.ArrayList;
import java.util.List;

import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The create-job rules — pure logic, no database (the repository is never touched here). */
class JobControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobController controller = new JobController(null, null, new SkillTaxonomy(mapper), mapper);

    private JobController.CreateJobRequest req(List<String> must, List<String> nice, Integer minExp) {
        return new JobController.CreateJobRequest("DevOps Engineer", "Own our platform", must, nice, minExp, false);
    }

    @Test
    void spellingVariantsBecomeOneCanonicalSkill() {
        var json = controller.requirementsFor(req(List.of("Spring Boot", "springboot", "SPRING-BOOT"), List.of(), 24),
                new ArrayList<>());
        assertThat(json.path("must_have").toString()).isEqualTo("[\"spring-boot\"]");
    }

    @Test
    void unknownSkillsAreKeptAndReported() {
        List<String> unknown = new ArrayList<>();
        var json = controller.requirementsFor(req(List.of("kubernetes", "Golang2"), List.of(), 24), unknown);
        assertThat(json.path("must_have").toString()).isEqualTo("[\"kubernetes\",\"golang2\"]");
        assertThat(unknown).containsExactly("golang2");
    }

    @Test
    void aSkillInBothListsIsAMustHave() {
        var json = controller.requirementsFor(req(List.of("kubernetes"), List.of("Kubernetes", "aws"), 24),
                new ArrayList<>());
        assertThat(json.path("nice_to_have").toString()).isEqualTo("[\"aws\"]");
    }

    @Test
    void storedShapeIsWhatTheMatcherReads() {
        var json = controller.requirementsFor(req(List.of("kubernetes"), List.of("aws"), 36), new ArrayList<>());
        assertThat(json.toString()).isEqualTo("{\"weights\":\"default\",\"must_have\":[\"kubernetes\"],"
                + "\"nice_to_have\":[\"aws\"],\"min_exp_months\":36,\"fresher_friendly\":false}");
    }

    @Test
    void badInputGetsAClear400() {
        assertThatThrownBy(() -> controller.requirementsFor(req(List.of(), List.of("aws"), 24), new ArrayList<>()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("at least one must-have");
        assertThatThrownBy(() -> controller.requirementsFor(req(List.of("java"), List.of(), -1), new ArrayList<>()))
                .hasMessageContaining("between 0 and 600");
        assertThatThrownBy(() -> controller.requirementsFor(req(List.of("java"), List.of(), 601), new ArrayList<>()))
                .hasMessageContaining("between 0 and 600");
        assertThatThrownBy(() -> controller.create(new JobController.CreateJobRequest(
                "  ", null, List.of("java"), null, null, null)))
                .hasMessageContaining("title is required");
    }

    @Test
    void theAutocompleteListHasEveryCanonicalSkill() {
        List<String> names = new SkillTaxonomy(mapper).canonicalNames();
        assertThat(names).hasSize(150).contains("kubernetes", "spring-boot").isSorted();
    }
}
