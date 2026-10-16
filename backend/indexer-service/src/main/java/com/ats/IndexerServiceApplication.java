package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Indexer (the design board's "Search Service" write side): keeps a search-shaped copy
 * of every candidate in OpenSearch — the CQRS read model. Postgres stays the truth.
 */
@SpringBootApplication
public class IndexerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexerServiceApplication.class, args);
    }
}
