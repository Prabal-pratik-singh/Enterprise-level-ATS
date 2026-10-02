package com.ats.extractor;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ats.util.Dates;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Date math over the LLM-extracted experience list. Done in Java because an
 * LLM guessing at arithmetic is how scoring bugs are born.
 */
@Component
public class ExperienceMath {

    private record Interval(YearMonth start, YearMonth end) {
    }

    /**
     * Total months worked, counting overlapping periods ONCE. Method:
     * parse every role into a [start, end] interval → sort by start →
     * merge intervals that overlap → sum the merged lengths.
     * Example: Jan2020-Dec2021 plus Jan2021-Dec2022 = 36 months, not 48.
     */
    public int totalExperienceMonths(JsonNode experienceArray, YearMonth now) {
        List<Interval> intervals = new ArrayList<>();
        for (JsonNode role : experienceArray) {
            Optional<YearMonth> start = Dates.parseYearMonth(role.path("start_date").asText(null));
            if (start.isEmpty()) {
                continue; // no readable start date -> this role can't be counted
            }
            boolean current = role.path("current").asBoolean(false);
            String endRaw = role.path("end_date").asText(null);
            // Ongoing role (or no end date given): count up to "now"
            YearMonth end = current || endRaw == null || endRaw.isBlank()
                    ? now
                    : Dates.parseYearMonth(endRaw).orElse(now);
            if (end.isAfter(now)) {
                end = now; // claims into the future are clamped to today
            }
            if (end.isBefore(start.get())) {
                continue; // ends before it starts -> nonsense, skip
            }
            intervals.add(new Interval(start.get(), end));
        }
        if (intervals.isEmpty()) {
            return 0;
        }

        // Sort by start month so overlaps sit next to each other
        intervals.sort((a, b) -> a.start().compareTo(b.start()));

        // Sweep and merge: extend the current block while the next one overlaps it
        int totalMonths = 0;
        YearMonth blockStart = intervals.get(0).start();
        YearMonth blockEnd = intervals.get(0).end();
        for (int i = 1; i < intervals.size(); i++) {
            Interval next = intervals.get(i);
            if (!next.start().isAfter(blockEnd)) { // overlap (or touch) -> merge
                if (next.end().isAfter(blockEnd)) {
                    blockEnd = next.end();
                }
            } else { // gap -> close the block, start a new one
                totalMonths += monthsInclusive(blockStart, blockEnd);
                blockStart = next.start();
                blockEnd = next.end();
            }
        }
        totalMonths += monthsInclusive(blockStart, blockEnd);
        return totalMonths;
    }

    /** Jan 2020 .. Dec 2020 = 12 months (both ends count). */
    private static int monthsInclusive(YearMonth start, YearMonth end) {
        return (int) ChronoUnit.MONTHS.between(start, end) + 1;
    }

    /** Simple demo buckets — the matcher refines with job requirements later. */
    public String seniority(int totalMonths) {
        if (totalMonths < 24) {
            return "junior";
        }
        if (totalMonths <= 60) {
            return "mid";
        }
        if (totalMonths <= 120) {
            return "senior";
        }
        return "lead";
    }

    /**
     * How completely filled is this resume? 0..1, a weighted checklist.
     * Feeds candidate_profiles.completeness and later the ResumeQuality score.
     */
    public double completeness(JsonNode profile) {
        double score = 0;
        score += hasText(profile.at("/contact/email")) ? 0.15 : 0;
        score += hasText(profile.at("/contact/phone")) ? 0.10 : 0;
        score += hasText(profile.path("summary")) ? 0.10 : 0;
        score += profile.path("experience").size() >= 1 ? 0.25 : 0;
        score += profile.path("education").size() >= 1 ? 0.15 : 0;
        score += profile.path("skills").size() >= 3 ? 0.15 : 0;
        score += profile.path("projects").size() >= 1 ? 0.05 : 0;
        score += profile.path("certifications").size() >= 1 ? 0.05 : 0;
        return Math.round(score * 100.0) / 100.0; // keep it a tidy 2-decimal number
    }

    private static boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }
}
