package com.ats.fraud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Check 5 — what the Dedup Service found. Dedup writes the sentence; this check only
 * decides what each kind COSTS. One flag per kind (the strongest match), so ten copies
 * of one resume don't pile up into certainty on their own.
 */
@Component
public class DuplicateCheck implements TrustCheck {

    static final Map<String, Double> WEIGHTS = Map.of(
            "DUPLICATE_FILE", 0.6,      // the same document under two names — strong on its own
            "COPIED_CONTENT", 0.5,      // ≥70% identical work history — enough to send to review
            "REPEAT_APPLICANT", 0.0);   // the same person again — shown as a note, costs nothing

    @Override
    public List<FraudFlag> check(TrustInput in) {
        Map<String, TrustInput.DuplicateMatch> strongest = new LinkedHashMap<>();
        for (TrustInput.DuplicateMatch m : in.duplicates()) {
            strongest.merge(m.kind(), m, (x, y) -> y.similarity() > x.similarity() ? y : x);
        }
        List<FraudFlag> flags = new ArrayList<>();
        strongest.forEach((kind, m) -> {
            long others = in.duplicates().stream().filter(d -> d.kind().equals(kind)).count() - 1;
            flags.add(new FraudFlag(kind, WEIGHTS.getOrDefault(kind, 0.0),
                    m.explanation() + (others > 0 ? " (and %d more)".formatted(others) : "")));
        });
        return flags;
    }
}
