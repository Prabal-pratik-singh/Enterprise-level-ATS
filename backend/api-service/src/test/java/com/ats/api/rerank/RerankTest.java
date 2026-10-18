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

    // ---- fairness: verdicts resting on bias proxies are thrown away whole ----

    @Test
    void aVerdictUsingABiasProxyIsDiscardedAGenuineOneIsKept() {
        String answer = """
                {"results": [
                  {"id": 1, "adjustment_percent": -5, "summary": "Strong Kafka.\\nSolid Java.\\nGood fit.",
                   "red_flags": ["potential flight risk"]},
                  {"id": 2, "adjustment_percent": -4, "summary": "Strong Kafka.\\nNo Java on the card.\\nRisky.",
                   "red_flags": ["no Java experience on the card"]}]}""";
        var verdicts = RerankPrompts.parse(mapper, answer, batch, "m");
        assertThat(verdicts).extracting(RerankPrompts.Verdict::name).containsExactly("Candidate B");
    }

    @Test
    void biasTermsAreCaughtWithoutFalseAlarmsOnTechWords() {
        assertThat(RerankPrompts.BIAS_PROXY.matcher("may be overqualified").find()).isTrue();
        assertThat(RerankPrompts.BIAS_PROXY.matcher("an older candidate").find()).isTrue();
        assertThat(RerankPrompts.BIAS_PROXY.matcher("probably 45 years old").find()).isTrue();
        assertThat(RerankPrompts.BIAS_PROXY.matcher("Flight-risk given tenure").find()).isTrue();
        // tech and job words that merely CONTAIN the letters must not trip it
        assertThat(RerankPrompts.BIAS_PROXY.matcher("golang, kafka, storage, message, race-free code").find()).isFalse();
        // skill RECENCY is a fair signal — the matcher itself writes "java (older experience)"
        assertThat(RerankPrompts.BIAS_PROXY.matcher("only older Java experience; spring-boot (older experience)")
                .find()).isFalse();
    }

    // ---- the retry round for candidates the model left out ----

    /** Answers only the FIRST card of every batch — like the model that skipped 5 of 50. */
    static class ForgetfulLlm implements com.ats.llm.LlmClient {
        int calls;

        @Override
        public String complete(String s, String u, String schema) {
            throw new AssertionError("rerank must go straight to the stronger model");
        }

        @Override
        public String completeWithStrongerModel(String s, String u, String schema) {
            calls++;
            return "{\"results\": [{\"id\": 1, \"adjustment_percent\": 2, \"summary\": \"a\\nb\\nc\"}]}";
        }
    }

    @Test
    void skippedCandidatesGetExactlyOneMoreRound() {
        ForgetfulLlm llm = new ForgetfulLlm();
        java.util.List<UUID> recorded = new java.util.ArrayList<>();
        RerankRecorder recorder = new RerankRecorder(null, null, null, null, mapper) {
            @Override
            public void record(UUID jobId, RerankPrompts.Verdict v) {
                recorded.add(v.applicationId());
            }
        };
        Reranker reranker = new Reranker(null, null, null, recorder, mapper);
        List<RerankCard> six = java.util.stream.IntStream.range(0, 6).mapToObj(i -> card("C" + i)).toList();
        JobBrief job = new JobBrief("Backend", List.of("java"), List.of(), 24);

        var left = reranker.judgeAll(llm, job, UUID.randomUUID(), six, "m");          // 2 batches → 2 judged
        assertThat(left).hasSize(4);
        var stillLeft = reranker.judgeAll(llm, job, UUID.randomUUID(), left, "m");    // the retry round
        assertThat(stillLeft).hasSize(3);
        assertThat(recorded).hasSize(3);
        assertThat(llm.calls).isEqualTo(3);
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
