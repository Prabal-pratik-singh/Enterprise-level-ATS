package com.ats.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenSearch settings (app.search.*). Only services that set app.search.base-url
 * (the indexer now, the api for search in Batch 3) get an OpenSearchClient bean.
 */
@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        String baseUrl,             // e.g. http://opensearch:9200 (env OPENSEARCH_URL)
        int connectTimeoutSeconds,  // spec: 5s to connect
        int readTimeoutSeconds) {   // spec: 10s for non-LLM calls
}
