package com.ats.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingClientTest {

    private static ArrayNode numbers(int count) {
        ArrayNode array = new ObjectMapper().createArrayNode();
        for (int i = 0; i < count; i++) {
            array.add(i / 1000.0);
        }
        return array;
    }

    @Test
    void a768NumberAnswerBecomesAVector() {
        float[] vector = EmbeddingClient.toVector(numbers(768));
        assertThat(vector).hasSize(768);
        assertThat(vector[767]).isEqualTo(0.767f);
    }

    @Test
    void aDifferentModelSizeFailsLoudlyInsteadOfBeingStored() {
        // e.g. someone switches to a 1024-dim model: every stored comparison would be garbage
        assertThatThrownBy(() -> EmbeddingClient.toVector(numbers(1024)))
                .hasMessageContaining("expected 768 dimensions, got 1024");
    }
}
