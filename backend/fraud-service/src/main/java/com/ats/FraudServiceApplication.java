package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Trust Service: listens to resume.extracted, runs explainable fraud checks and
 * FLAGS suspicious applications for a human — it never rejects anyone itself.
 */
@SpringBootApplication
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
