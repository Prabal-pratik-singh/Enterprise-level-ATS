package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pure Kafka worker: resume.extracted in, application.scored out. All inputs
 * come from Postgres — no LLM, no files: fast and deterministic.
 */
@SpringBootApplication
public class MatcherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatcherServiceApplication.class, args);
    }
}
