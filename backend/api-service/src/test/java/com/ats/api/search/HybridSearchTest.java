package com.ats.api.search;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HybridSearchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- RRF ----

    @Test
    void foundByBothBeatsFoundByOne() {
        var fused = HybridRanker.fuse(List.of("rohan", "priya"), List.of("rohan", "aditya"));
        assertThat(fused).extracting(HybridRanker.Fused::id).first().isEqualTo("rohan");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 61, within(1e-12)); // the k=60 maths
    }

    @Test
    void labelsSayWhereEachResultCameFrom() {
        var fused = HybridRanker.fuse(List.of("rohan", "priya"), List.of("rohan", "aditya", "neha"));
        assertThat(fused).extracting(f -> f.id() + ":" + f.source())
                .contains("rohan:both", "priya:keyword", "aditya:semantic");
        var aditya = fused.stream().filter(f -> f.id().equals("aditya")).findFirst().orElseThrow();
        assertThat(aditya.keywordRank()).isNull();
        assertThat(aditya.semanticRank()).isEqualTo(2);
    }

    @Test
    void emptyListsGiveNothing() {
        assertThat(HybridRanker.fuse(List.of(), List.of())).isEmpty();
        assertThat(HybridRanker.fuse(List.of("a"), List.of())).extracting(HybridRanker.Fused::source)
                .containsExactly("keyword");
    }

    // ---- queries ----

    private ArrayNode filters() {
        return SearchQueries.filters(mapper, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                SearchController.parseSkills(" Kafka, java,,"), 24, true);
    }

    @Test
    void everyFilterGoesIntoBothQueries() {
        ArrayNode filters = filters();
        assertThat(filters).hasSize(5); // job + 2 skills + minExp + hideFlagged
        ObjectNode keyword = SearchQueries.keyword(mapper, "event driven backend", filters);
        ObjectNode semantic = SearchQueries.semantic(mapper, new float[768], filters);
        assertThat(keyword.at("/query/bool/filter")).isEqualTo(filters);
        assertThat(semantic.at("/query/knn/embedding/filter/bool/filter")).isEqualTo(filters);
    }

    @Test
    void skillsAreLowerCasedAndEachBecomesItsOwnFilter() {
        assertThat(SearchController.parseSkills(" Kafka, java,, kafka")).containsExactly("kafka", "java");
        assertThat(filters().get(1).at("/term/skills").asText()).isEqualTo("kafka");
        assertThat(filters().get(2).at("/term/skills").asText()).isEqualTo("java");
    }

    @Test
    void shortQueriesNeedEveryWord() {
        ObjectNode keyword = SearchQueries.keyword(mapper, "event driven backend", filters());
        assertThat(keyword.at("/query/bool/must/multi_match/minimum_should_match").asText()).isEqualTo("3<75%");
    }

    @Test
    void theVectorIsNeverSentBackToTheBrowser() {
        ObjectNode semantic = SearchQueries.semantic(mapper, new float[768], filters());
        assertThat(semantic.path("_source").toString()).doesNotContain("embedding");
        assertThat(semantic.at("/query/knn/embedding/vector")).hasSize(768);
    }
}
