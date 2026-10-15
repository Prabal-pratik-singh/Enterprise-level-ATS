package com.ats.embedder;

import com.ats.embedding.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbedderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void profileTextKeepsEverySection() throws Exception {
        String text = ProfileText.of(mapper.readTree("""
                {"summary": "Backend engineer.",
                 "skills": ["kafka", "java"],
                 "experience": [{"title": "Engineer", "company": "OrbitPay", "description": "Built payment pipelines."}],
                 "projects": [{"name": "Ledger", "description": "Double-entry ledger in Postgres."}]}"""));
        assertThat(text).isEqualTo("""
                Backend engineer.
                Skills: kafka, java
                Engineer at OrbitPay: Built payment pipelines.
                Project Ledger: Double-entry ledger in Postgres.
                """);
    }

    @Test
    void missingSectionsAreSkippedNotCrashed() throws Exception {
        assertThat(ProfileText.of(mapper.readTree("{\"skills\": [\"java\"]}"))).isEqualTo("Skills: java\n");
        assertThat(ProfileText.of(mapper.readTree("{}"))).isEmpty();
    }

    @Test
    void pgvectorTextFormat() {
        assertThat(EmbeddingClient.toPgVector(new float[] {0.5f, -1.25f, 0f})).isEqualTo("[0.5,-1.25,0.0]");
    }
}
