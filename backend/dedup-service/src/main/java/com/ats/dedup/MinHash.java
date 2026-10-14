package com.ats.dedup;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * MinHash: squeezes a document's set of shingles into 128 numbers such that
 * the fraction of positions where two signatures agree ≈ the Jaccard
 * similarity of the two sets (|A ∩ B| / |A ∪ B|) — without ever comparing
 * the full sets.
 *
 * Each of the 128 "permutations" is a cheap hash h(x) = (a·x + b) mod p; a
 * signature slot keeps the SMALLEST h over all shingles. Two documents share
 * that minimum exactly when the shingle that wins is in both sets, which
 * happens with probability = Jaccard. 128 slots → error about ±0.09.
 */
public final class MinHash {

    public static final int NUM_PERM = 128;          // spec: 128 permutations
    public static final int SHINGLE_WORDS = 5;       // "built kafka pipelines for payments" = one shingle
    private static final long PRIME = 2_147_483_647L; // 2^31 − 1: a·x stays inside a long (2^31 · 2^31 < 2^63)
    private static final long SEED = 20_261_014L;     // FIXED — signatures must match across restarts and replicas

    private final long[] a = new long[NUM_PERM];
    private final long[] b = new long[NUM_PERM];

    public MinHash() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int i = 0; i < NUM_PERM; i++) {
            a[i] = 1 + random.nextLong(PRIME - 1);   // a ≠ 0, otherwise every shingle hashes to b
            b[i] = random.nextLong(PRIME);
        }
    }

    /** Overlapping word k-grams of lower-cased text: punctuation and case never matter. */
    public static Set<String> shingles(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+");
        String[] clean = Arrays.stream(words).filter(w -> !w.isEmpty()).toArray(String[]::new);
        for (int i = 0; i + SHINGLE_WORDS <= clean.length; i++) {
            out.add(String.join(" ", Arrays.copyOfRange(clean, i, i + SHINGLE_WORDS)));
        }
        return out;
    }

    /** 128 minimums. An empty set gets all-MAX slots, which never match a real document's. */
    public long[] signature(Set<String> shingles) {
        long[] sig = new long[NUM_PERM];
        Arrays.fill(sig, Long.MAX_VALUE);
        for (String shingle : shingles) {
            long x = fnv1a(shingle) % PRIME;
            for (int i = 0; i < NUM_PERM; i++) {
                long h = (a[i] * x + b[i]) % PRIME;
                if (h < sig[i]) {
                    sig[i] = h;
                }
            }
        }
        return sig;
    }

    /** Estimated Jaccard similarity: the share of slots where the two signatures agree. */
    public static double similarity(long[] s1, long[] s2) {
        int same = 0;
        for (int i = 0; i < NUM_PERM; i++) {
            if (s1[i] == s2[i] && s1[i] != Long.MAX_VALUE) {
                same++;
            }
        }
        return (double) same / NUM_PERM;
    }

    /** Exact Jaccard — only for tests, to check the estimate. */
    static double jaccard(Set<String> x, Set<String> y) {
        if (x.isEmpty() && y.isEmpty()) {
            return 0;
        }
        long inter = x.stream().filter(y::contains).count();
        return (double) inter / (x.size() + y.size() - inter);
    }

    /** 64-bit FNV-1a, made non-negative: stable across JVMs (String.hashCode is too, but only 32 bits). */
    private static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L;
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= by;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
