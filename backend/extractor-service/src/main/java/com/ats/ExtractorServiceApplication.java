package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Pure Kafka worker: resume.parsed in, resume.extracted out. No HTTP server. */
@SpringBootApplication
public class ExtractorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractorServiceApplication.class, args);
    }
}
