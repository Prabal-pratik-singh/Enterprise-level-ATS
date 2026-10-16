package com.ats.api.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion: merge two ranked lists without comparing their scores
 * (BM25 scores like 7.3 and cosine scores like 0.82 live on different scales).
 * Each list gives a document 1 / (k + rank); a document in BOTH lists gets both shares.
 *
 * Example: keyword #1 + semantic #1 → 1/61 + 1/61 = 0.0328; semantic #3 only → 1/63 = 0.0159.
 */
final class HybridRanker {

    static final int K = 60;   // spec: RRF k = 60 — keeps rank 1 from drowning out ranks 2, 3, ...

    record Fused(String id, double score, Integer keywordRank, Integer semanticRank) {

        /** The match-source hint the UI shows: "keyword", "semantic" or "both". */
        String source() {
            if (keywordRank != null && semanticRank != null) {
                return "both";
            }
            return keywordRank != null ? "keyword" : "semantic";
        }
    }

    static List<Fused> fuse(List<String> keywordIds, List<String> semanticIds) {
        Map<String, Integer> keyword = ranks(keywordIds);    // id → 1-based position
        Map<String, Integer> semantic = ranks(semanticIds);
        Set<String> all = new LinkedHashSet<>(keywordIds);
        all.addAll(semanticIds);
        List<Fused> fused = new ArrayList<>();
        for (String id : all) {
            Integer k = keyword.get(id);
            Integer s = semantic.get(id);
            double score = (k == null ? 0 : 1.0 / (K + k)) + (s == null ? 0 : 1.0 / (K + s));
            fused.add(new Fused(id, score, k, s));
        }
        fused.sort(Comparator.comparingDouble(Fused::score).reversed());
        return fused;
    }

    private static Map<String, Integer> ranks(List<String> ids) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            ranks.putIfAbsent(ids.get(i), i + 1);
        }
        return ranks;
    }

    private HybridRanker() {
    }
}
