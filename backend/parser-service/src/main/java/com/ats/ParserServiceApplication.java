package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pure Kafka worker — no HTTP server (see application.yml). Lives at the
 * com.ats root so component scan finds the shared ats-common beans too.
 */
@SpringBootApplication
public class ParserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParserServiceApplication.class, args);
    }
}
