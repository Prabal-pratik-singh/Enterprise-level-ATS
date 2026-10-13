package com.ats.fraud;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ats.util.Dates;
import com.fasterxml.jackson.databind.JsonNode;

/** The jobs on a resume as real date ranges — shared by the timeline and anachronism checks. */
final class Roles {

    record Role(String title, String company, YearMonth start, YearMonth end, String description) {

        /** "Backend Developer at OrbitPay (2022-01 → 2025-05)" — how roles appear in explanations. */
        String label() {
            return "%s at %s (%s → %s)".formatted(
                    title == null ? "a role" : title, company == null ? "an unnamed company" : company, start, end);
        }
    }

    /** Roles with a usable start date. "current" or a missing end date means "until today". */
    static List<Role> parse(JsonNode profile, YearMonth today) {
        List<Role> roles = new ArrayList<>();
        for (JsonNode job : profile.path("experience")) {
            Optional<YearMonth> start = Dates.parseYearMonth(job.path("start_date").asText(null));
            if (start.isEmpty()) {
                continue; // no start date = nothing we can check against a calendar
            }
            YearMonth end = job.path("current").asBoolean(false)
                    ? today
                    : Dates.parseYearMonth(job.path("end_date").asText(null)).orElse(today);
            if (end.isBefore(start.get())) {
                continue; // garbled dates — not our job to judge typos
            }
            roles.add(new Role(job.path("title").asText(null), job.path("company").asText(null),
                    start.get(), end, job.path("description").asText("")));
        }
        return roles;
    }

    /** Months two roles share, counting both ends: Jan..Mar vs Mar..Jun share 1 month (March). */
    static int overlapMonths(Role a, Role b) {
        YearMonth from = a.start().isAfter(b.start()) ? a.start() : b.start();
        YearMonth to = a.end().isBefore(b.end()) ? a.end() : b.end();
        return from.isAfter(to) ? 0 : (int) ChronoUnit.MONTHS.between(from, to) + 1;
    }

    private Roles() {
    }
}
