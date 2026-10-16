package com.ats.indexer;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateDocumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateDocuments documents = new CandidateDocuments(null, mapper); // toDocument needs no DB

    private static final String PROFILE = """
            {"summary": "Backend Developer with 6 years of experience.",
             "skills": ["java", "spring-boot", "kafka"],
             "contact": {"location": "Pune, India"},
             "derived": {"seniority": "senior", "total_experience_months": 69},
             "experience": [{"title": "Backend Developer", "company": "Veritas Health",
                             "description": "Optimized kafka queries."}],
             "projects": [{"name": "Inventory Sync Engine", "description": "Synced stock levels."}]}""";

    private CandidateDocuments.Row row(String embedding) {
        return new CandidateDocuments.Row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "FLAGGED_FOR_REVIEW", "Aditya Joshi", "aditya@yahoo.com", 53.55, 88.55, 0.7, PROFILE, embedding);
    }

    @Test
    void aProfileBecomesASearchDocument() {
        ObjectNode doc = documents.toDocument(row("[0.5,-0.25,0.125]"));
        assertThat(doc.path("name").asText()).isEqualTo("Aditya Joshi");
        assertThat(doc.path("status").asText()).isEqualTo("FLAGGED_FOR_REVIEW");
        assertThat(doc.path("final_score").asDouble()).isEqualTo(53.55);
        assertThat(doc.path("skills")).hasSize(3);
        assertThat(doc.path("skills_text").asText()).isEqualTo("java spring-boot kafka");
        assertThat(doc.path("experience").asText()).isEqualTo("Backend Developer at Veritas Health: Optimized kafka queries.\n");
        assertThat(doc.path("projects").asText()).isEqualTo("Inventory Sync Engine: Synced stock levels.\n");
        assertThat(doc.path("total_experience_months").asInt()).isEqualTo(69);
        assertThat(doc.path("location").asText()).isEqualTo("Pune, India");
        assertThat(doc.path("embedding")).hasSize(3);
        assertThat(doc.path("embedding").get(1).asDouble()).isEqualTo(-0.25);
    }

    @Test
    void notYetEmbeddedMeansNoVectorFieldRatherThanACrash() {
        ObjectNode doc = documents.toDocument(row(null));
        assertThat(doc.has("embedding")).isFalse();
        assertThat(doc.path("name").asText()).isEqualTo("Aditya Joshi"); // still findable by keyword
    }
}
