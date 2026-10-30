package com.ats.extractor;

import java.util.ArrayList;
import java.util.List;

import com.ats.llm.LlmClient;
import com.ats.profile.ExperienceMath;
import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The case that failed Prabal's real resume on 2026-10-17: the normal model answers with
 * invalid JSON twice. The stronger model now gets one last try before the resume is given up.
 */
class StrongerModelFallbackTest {

    private static final String VALID = """
            {"contact": {"name": "Prabal Pratik Singh", "email": "prabal@example.com", "phone": null, "location": null},
             "experience": [], "education": [{"degree": "B.Tech", "institution": "NIT", "year": 2025}],
             "skills": ["java", "spring-boot"]}""";

    /** A scripted LLM: the normal model's answers in order, and one answer for the stronger model. */
    static class FakeLlm implements LlmClient {
        final List<String> normalAnswers;
        final String strongAnswer;
        final List<String> calls = new ArrayList<>();

        FakeLlm(List<String> normalAnswers, String strongAnswer) {
            this.normalAnswers = new ArrayList<>(normalAnswers);
            this.strongAnswer = strongAnswer;
        }

        @Override
        public String complete(String system, String user, String schema) {
            calls.add("normal");
            return normalAnswers.remove(0);
        }

        @Override
        public String completeWithStrongerModel(String system, String user, String schema) {
            calls.add("strong");
            return strongAnswer;
        }
    }

    private ProfileExtractor extractor(LlmClient llm) {
        ObjectMapper mapper = new ObjectMapper();
        // UnmatchedSkills is null on purpose: every skill in VALID is in the dictionary, so it is never called
        return new ProfileExtractor(llm, new ProfileValidator(mapper), new SkillTaxonomy(mapper), null,
                new ExperienceMath(), mapper, new RuleExtractor(mapper));
    }

    @Test
    void twoInvalidAnswersThenTheStrongerModelSavesTheResume() {
        FakeLlm llm = new FakeLlm(List.of("not json at all", "{\"still\": \"wrong\"}"), VALID);
        var result = extractor(llm).extract("Prabal Pratik Singh — Java developer, B.Tech 2025");
        assertThat(llm.calls).containsExactly("normal", "normal", "strong");
        assertThat(result.profile().path("contact").path("name").asText()).isEqualTo("Prabal Pratik Singh");
    }

    @Test
    void aGoodFirstAnswerNeverTouchesTheStrongerModel() {
        FakeLlm llm = new FakeLlm(List.of(VALID), "unused");
        extractor(llm).extract("Prabal Pratik Singh — Java developer");
        assertThat(llm.calls).containsExactly("normal");
    }

    @Test
    void ifEvenTheStrongerModelFailsWeGiveUpLoudly() {
        FakeLlm llm = new FakeLlm(List.of("x", "y"), "z");
        assertThatThrownBy(() -> extractor(llm).extract("some resume"))
                .hasMessageContaining("three times (last: stronger model)");
    }
}
