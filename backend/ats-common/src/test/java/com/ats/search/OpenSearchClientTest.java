package com.ats.search;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchClientTest {

    @Test
    void bulkBodyIsActionLineThenDocumentPerDocumentEndingInANewline() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode a = mapper.createObjectNode().put("application_id", "a-1").put("name", "Priya");
        ObjectNode b = mapper.createObjectNode().put("application_id", "b-2").put("name", "Rahul");
        assertThat(OpenSearchClient.bulkBody(List.of(a, b))).isEqualTo("""
                {"index":{"_index":"candidates","_id":"a-1"}}
                {"application_id":"a-1","name":"Priya"}
                {"index":{"_index":"candidates","_id":"b-2"}}
                {"application_id":"b-2","name":"Rahul"}
                """);
    }
}
