package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Embedder: turns every extracted resume into 768 numbers that capture its
 * meaning, so Phase 7's search can find "Kafka people" for "event driven backend".
 */
@SpringBootApplication
public class EmbedderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbedderServiceApplication.class, args);
    }
}
