package com.ats.matcher;

import java.util.List;
import java.util.Map;

/**
 * The outcome of scoring one candidate against one job — everything the
 * matcher persists and the dashboard explains.
 */
public record ScoreCard(
        double finalScore,              // 0..100, already capped and weighted
        Map<String, Double> components, // each 0..1: skill_match, experience_fit, ...
        List<Evidence> evidence,        // the human-readable "why"
        boolean knockedOut,             // failed a hard filter (still recorded, never hidden)
        double mustHaveCap) {           // 1.0 = no cap; 0.5 = half the ceiling is gone

    /** One explainable chip: {"type":"skill","text":"matched must-have: java (recent)","positive":true} */
    public record Evidence(String type, String text, boolean positive) {
    }
}
