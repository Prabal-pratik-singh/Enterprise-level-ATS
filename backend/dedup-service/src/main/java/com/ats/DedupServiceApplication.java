package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dedup Service: listens to resume.extracted and finds the same file, the same
 * person or a copied work history among earlier applications. It only RECORDS
 * matches — the Trust Service decides what they cost.
 */
@SpringBootApplication
public class DedupServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DedupServiceApplication.class, args);
    }
}
