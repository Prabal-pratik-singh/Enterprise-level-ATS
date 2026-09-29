package com.ats.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Skill normalization is fraud- and scoring-critical → spec demands unit tests. */
class SkillTaxonomyTest {

    private final SkillTaxonomy taxonomy = new SkillTaxonomy(new ObjectMapper());

    @Test
    void mapsAliasesAndSpellingVariantsToCanonicalNames() {
        assertThat(taxonomy.canonical("ReactJS")).contains("react");
        assertThat(taxonomy.canonical("React.js")).contains("react");
        assertThat(taxonomy.canonical("Spring Boot")).contains("spring-boot");
        assertThat(taxonomy.canonical("SpringBoot")).contains("spring-boot");
        assertThat(taxonomy.canonical("K8s")).contains("kubernetes");
        assertThat(taxonomy.canonical("Postgres")).contains("postgresql");
        assertThat(taxonomy.canonical("NODE JS")).contains("node.js");
    }

    @Test
    void canonicalNamesMapToThemselves() {
        assertThat(taxonomy.canonical("java")).contains("java");
        assertThat(taxonomy.canonical("kafka")).contains("kafka");
    }

    @Test
    void unknownSkillsComeBackEmptyInsteadOfGuessing() {
        assertThat(taxonomy.canonical("underwater-basket-weaving")).isEmpty();
        assertThat(taxonomy.canonical("")).isEmpty();
        assertThat(taxonomy.canonical(null)).isEmpty();
    }

    @Test
    void releaseYearsSupportTheAnachronismCheck() {
        assertThat(taxonomy.firstReleaseYear("kubernetes")).contains(2014);
        assertThat(taxonomy.firstReleaseYear("java")).contains(1995);
        // concepts have no birth year — Optional.empty, not a fake number
        assertThat(taxonomy.firstReleaseYear("microservices")).isEmpty();
    }
}
