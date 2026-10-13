package com.ats.fraud;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Check 1 — dates that cannot all be true:
 *  a) two FULL-TIME jobs running in parallel for months, and
 *  b) more years of experience claimed than have passed since graduation.
 */
@Component
public class TimelineCheck implements TrustCheck {

    static final double OVERLAP_WEIGHT = 0.5;
    static final double IMPOSSIBLE_CLAIM_WEIGHT = 0.5;

    // Notice periods and hand-overs: a few months of overlap between jobs is normal.
    static final int ALLOWED_OVERLAP_MONTHS = 3;
    // People round "7 years 4 months" up to "8 years" — only call it impossible past this margin.
    static final int CLAIM_TOLERANCE_MONTHS = 6;
    // Only the year is known; most degrees finish around June.
    static final int ASSUMED_GRADUATION_MONTH = 6;

    // Titles that can legitimately run alongside a full-time job
    private static final Pattern NOT_FULL_TIME = Pattern.compile(
            "intern|freelanc|part[- ]?time|contract|consult|advis|volunteer|mentor|teaching",
            Pattern.CASE_INSENSITIVE);

    private final SkillTaxonomy taxonomy;

    public TimelineCheck(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public List<FraudFlag> check(TrustInput in) {
        List<FraudFlag> flags = new ArrayList<>();
        worstOverlap(in).ifPresent(flags::add);
        impossibleClaim(in).ifPresent(flags::add);
        return flags;
    }

    /** Report only the worst overlapping pair — one clear sentence beats a list of five. */
    private Optional<FraudFlag> worstOverlap(TrustInput in) {
        List<Roles.Role> fullTime = Roles.parse(in.profile(), in.today()).stream()
                .filter(role -> role.title() == null || !NOT_FULL_TIME.matcher(role.title()).find())
                .toList();
        int worst = 0;
        Roles.Role first = null;
        Roles.Role second = null;
        for (int i = 0; i < fullTime.size(); i++) {
            for (int j = i + 1; j < fullTime.size(); j++) { // every pair once
                int months = Roles.overlapMonths(fullTime.get(i), fullTime.get(j));
                if (months > worst) {
                    worst = months;
                    first = fullTime.get(i);
                    second = fullTime.get(j);
                }
            }
        }
        if (worst <= ALLOWED_OVERLAP_MONTHS) {
            return Optional.empty();
        }
        return Optional.of(new FraudFlag("OVERLAPPING_JOBS", OVERLAP_WEIGHT,
                "Two full-time jobs at the same time for %d months: %s and %s."
                        .formatted(worst, first.label(), second.label())));
    }

    private Optional<FraudFlag> impossibleClaim(TrustInput in) {
        OptionalInt graduationYear = StreamSupport.stream(in.profile().path("education").spliterator(), false)
                .map(edu -> edu.path("year"))
                .filter(JsonNode::canConvertToInt)
                .mapToInt(JsonNode::asInt)
                .filter(year -> year <= in.today().getYear()) // a fresher's FUTURE graduation proves nothing
                .min(); // the FIRST degree — a later master's doesn't erase earlier work
        if (graduationYear.isEmpty()) {
            return Optional.empty();
        }
        long monthsSinceGraduation = ChronoUnit.MONTHS.between(
                YearMonth.of(graduationYear.getAsInt(), ASSUMED_GRADUATION_MONTH), in.today());

        Optional<ExperienceClaims.Claim> biggest = ExperienceClaims
                .find(in.profile().path("summary").asText(null), taxonomy).stream()
                .max((a, b) -> Integer.compare(a.years(), b.years()));
        if (biggest.isEmpty() || biggest.get().years() * 12L <= monthsSinceGraduation + CLAIM_TOLERANCE_MONTHS) {
            return Optional.empty();
        }
        ExperienceClaims.Claim claim = biggest.get();
        String what = claim.skill() == null ? "experience" : claim.skill() + " experience";
        return Optional.of(new FraudFlag("IMPOSSIBLE_EXPERIENCE", IMPOSSIBLE_CLAIM_WEIGHT,
                "Claims at least %d years of %s, but graduated in %d — only about %d years ago."
                        .formatted(claim.years(), what, graduationYear.getAsInt(), monthsSinceGraduation / 12)));
    }
}
