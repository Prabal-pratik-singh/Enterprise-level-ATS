package com.ats.api.rerank;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules that keep an LLM's opinion bounded and honest. */
class RerankTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RerankCards builder = new RerankCards(null, mapper);   // card() needs no database

    private RerankCard card(String name) {
        String profile = """
                {"skills": ["java", "kafka"], "derived": {"total_experience_months": 60, "seniority": "senior"},
                 "experience": [{"title": "Backend Engineer", "company": "OrbitPay", "start_date": "2021-01",
                                 "current": true, "description": "Ran Kafka in production at 2M events/day."}],
                 "projects": [{"name": "Ledger"}], "contact": {"email": "secret@example.com"}}""";
        String evidence = "[{\"text\": \"matched must-have: kafka (recent)\", \"positive\": true}]";
        return new RerankCard(UUID.randomUUID(), name, 72.0, builder.card(profile, evidence, 72.0));
    }

    private final List<RerankCard> batch = List.of(card("Candidate A"), card("Candidate B"));

    @Test
    void adjustmentsAreClampedToTenPercent() {
        String answer = """
                {"results": [{"id": 1, "summary": "a\\nb\\nc", "adjustment_percent": 25},
                             {"id": 2, "summary": "a\\nb\\nc", "adjustment_percent": -40}]}""";
        var verdicts = RerankPrompts.parse(mapper, answer, batch, "test-model");
        assertThat(verdicts).extracting(RerankPrompts.Verdict::adjustment).containsExactly(0.10, -0.10);
    }

    @Test
    void idsOutsideTheBatchAndMalformedEntriesAreIgnored() {
        String answer = """
                {"results": [{"id": 7, "summary": "not in this batch", "adjustment_percent": 5},
                             {"id": 1, "summary": "", "adjustment_percent": 5},
                             {"id": 2, "summary": "good\\nsolid\\nhire", "adjustment_percent": 8},
                             {"id": 2, "summary": "duplicate", "adjustment_percent": -9}]}""";
        var verdicts = RerankPrompts.parse(mapper, answer, batch, "test-model");
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).name()).isEqualTo("Candidate B");
        assertThat(verdicts.get(0).adjustment()).isEqualTo(0.08);
        assertThat(verdicts.get(0).reasons().path("summary").asText()).isEqualTo("good\nsolid\nhire");
    }

    @Test
    void anUnreadableAnswerChangesNobody() {
        assertThat(RerankPrompts.parse(mapper, "sorry, I can't", batch, "m")).isEmpty();
    }

    @Test
    void cardsCarryEvidenceButNoContactDetailsOrVectors() {
        String prompt = RerankPrompts.user(mapper, new JobBrief("Backend Engineer", List.of("java", "kafka"),
                List.of("docker"), 24), batch);
        assertThat(prompt).contains("2M events/day", "matched must-have: kafka", "\"id\":1", "\"id\":2");
        assertThat(prompt).doesNotContain("secret@example.com", "embedding");
    }

    @Test
    void fiftyCandidatesBecomeTenCalls() {
        assertThat(Reranker.partition(java.util.Collections.nCopies(50, "x"), Reranker.BATCH)).hasSize(10);
        assertThat(Reranker.partition(java.util.Collections.nCopies(43, "x"), Reranker.BATCH)).hasSize(9);
    }

    @Test
    void noLlmMeansA503NotACrash() {
        Reranker noLlm = new Reranker(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(com.ats.llm.LlmClient.class), null, null, null, mapper);
        RerankController controller = new RerankController(null, noLlm);
        assertThatThrownBy(() -> controller.start(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no LLM configured");
    }
}
