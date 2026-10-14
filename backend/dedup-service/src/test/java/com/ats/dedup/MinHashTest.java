package com.ats.dedup;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Spec: "Unit-test MinHash similarity." */
class MinHashTest {

    private final MinHash minHash = new MinHash();

    static final String RESUME = "Senior Backend Engineer at OrbitPay. Built Kafka pipelines processing two million "
            + "payment events per day, migrated the ledger service from a monolith to Spring Boot microservices, "
            + "cut p95 latency by forty percent with Redis caching and led a team of four engineers.";

    private long[] sig(String text) {
        return minHash.signature(MinHash.shingles(text));
    }

    @Test
    void identicalTextIsOne() {
        assertThat(MinHash.similarity(sig(RESUME), sig(RESUME))).isEqualTo(1.0);
    }

    @Test
    void caseAndPunctuationDoNotMatter() {
        String shouty = RESUME.toUpperCase().replace(",", " ;").replace(".", " !");
        assertThat(MinHash.similarity(sig(RESUME), sig(shouty))).isEqualTo(1.0);
    }

    @Test
    void unrelatedTextIsNearZero() {
        String other = "Frontend developer at PixelCraft. Designed accessible React component libraries, "
                + "wrote Storybook docs and ran usability studies with thirty customers every quarter.";
        assertThat(MinHash.similarity(sig(RESUME), sig(other))).isLessThan(0.1);
    }

    @Test
    void estimateTracksTheExactJaccard() {
        // two sets of 300 shingles sharing 150 → exact Jaccard = 150 / 450 = 0.333
        Set<String> a = new LinkedHashSet<>();
        Set<String> b = new LinkedHashSet<>();
        for (int i = 0; i < 300; i++) {
            a.add("shingle " + i);
            b.add("shingle " + (i + 150));
        }
        double exact = MinHash.jaccard(a, b);
        assertThat(exact).isCloseTo(0.333, within(0.001));
        // 128 slots → standard error ≈ 0.04; 0.12 is three of those
        assertThat(MinHash.similarity(minHash.signature(a), minHash.signature(b))).isCloseTo(exact, within(0.12));
    }

    @Test
    void signaturesAreStableAcrossInstances() {
        // a fixed seed: the dedup service on Monday and its replica on Tuesday must agree
        assertThat(new MinHash().signature(MinHash.shingles(RESUME))).isEqualTo(sig(RESUME));
    }

    @Test
    void emptyTextMatchesNothing() {
        assertThat(MinHash.similarity(sig(""), sig(""))).isZero();
        assertThat(MinHash.similarity(sig(""), sig(RESUME))).isZero();
    }
}
