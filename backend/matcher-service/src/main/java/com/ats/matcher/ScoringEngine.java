package com.ats.matcher;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ats.matcher.ScoreCard.Evidence;
import com.ats.util.Dates;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Explainable scoring: six components, each 0..1, each leaving evidence chips.
 * Missing must-have skills CAP the final score (gate, not just a deduction).
 * Hard knock-outs still produce a ScoreCard — flags, never silent hiding.
 */
@Component
public class ScoringEngine {

    // Each missing must-have skill removes a quarter of the possible ceiling;
    // even missing them all leaves a 0.2 floor so the row stays visible/rankable.
    private static final double CAP_PER_MISSING_MUST = 0.25;
    private static final double CAP_FLOOR = 0.2;

    public ScoreCard score(JsonNode profile, JobRequirements job, YearMonth now) {
        Map<String, Double> components = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>();

        int months = profile.at("/derived/total_experience_months").asInt(0);

        // ---------- HARD KNOCK-OUT: way below the experience bar ----------
        // (fresher-friendly jobs never knock out on experience)
        boolean knockedOut = false;
        if (!job.fresherFriendly() && job.minExpMonths() > 0 && months < job.minExpMonths() / 2) {
            knockedOut = true;
            evidence.add(new Evidence("knockout",
                    "experience " + months + " months is below half the required "
                            + job.minExpMonths() + " months", false));
        }

        // ---------- 1. SkillMatch: must-haves gate, nice-to-haves add ----------
        Set<String> candidateSkills = new HashSet<>();
        profile.path("skills").forEach(s -> candidateSkills.add(s.asText("").toLowerCase(Locale.ROOT)));

        int matchedMust = 0;
        double mustRecencySum = 0;
        for (String must : job.mustHave()) {
            if (candidateSkills.contains(must)) {
                matchedMust++;
                double recency = recencyFactor(must, profile, now);
                mustRecencySum += recency;
                evidence.add(new Evidence("skill", "matched must-have: " + must
                        + (recency >= 0.9 ? " (recent)" : recency >= 0.6 ? " (older experience)" : " (listed only)"), true));
            } else {
                evidence.add(new Evidence("skill", "MISSING must-have: " + must, false));
            }
        }
        int matchedNice = 0;
        for (String nice : job.niceToHave()) {
            if (candidateSkills.contains(nice)) {
                matchedNice++;
                evidence.add(new Evidence("skill", "nice-to-have: " + nice, true));
            }
        }
        // Must-haves carry 70% of this component (recency-weighted), nice-to-haves 30%
        double mustPart = job.mustHave().isEmpty() ? 1.0 : mustRecencySum / job.mustHave().size();
        double nicePart = job.niceToHave().isEmpty() ? 0.0 : (double) matchedNice / job.niceToHave().size();
        components.put("skill_match", round(mustPart * 0.7 + nicePart * 0.3));

        // The GATE: each missing must-have lowers the ceiling of the FINAL score.
        int missingMust = job.mustHave().size() - matchedMust;
        double cap = Math.max(CAP_FLOOR, 1.0 - CAP_PER_MISSING_MUST * missingMust);

        // ---------- 2. ExperienceFit ----------
        components.put("experience_fit", round(experienceFit(months, job, evidence)));

        // ---------- 3. ProjectRelevance: token overlap with the job (Jaccard) ----------
        components.put("project_relevance", round(projectRelevance(profile, job, evidence)));

        // ---------- 4. Education ----------
        components.put("education", round(education(profile, evidence)));

        // ---------- 5. Certifications ----------
        int certs = profile.path("certifications").size();
        components.put("certifications", certs >= 2 ? 1.0 : certs == 1 ? 0.7 : 0.3);
        if (certs > 0) {
            evidence.add(new Evidence("certification", certs + " certification(s) listed", true));
        }

        // ---------- 6. ResumeQuality = extractor's completeness ----------
        components.put("resume_quality", round(profile.at("/derived/completeness").asDouble(0.5)));

        // ---------- Weighted blend, then the gate, then knock-out crush ----------
        JobRequirements.Weights w = job.weights();
        double blended = components.get("skill_match") * w.skillMatch()
                + components.get("experience_fit") * w.experienceFit()
                + components.get("project_relevance") * w.projectRelevance()
                + components.get("education") * w.education()
                + components.get("certifications") * w.certifications()
                + components.get("resume_quality") * w.resumeQuality();
        double finalScore = 100.0 * blended * cap;
        if (knockedOut) {
            finalScore = Math.min(finalScore, 15.0); // visible at the bottom, never hidden
        }
        if (missingMust > 0) {
            evidence.add(new Evidence("gate", missingMust + " missing must-have(s) cap the score at "
                    + Math.round(cap * 100) + "%", false));
        }

        return new ScoreCard(round(finalScore), components, evidence, knockedOut, cap);
    }

    /**
     * Recency decay: a skill used in a CURRENT/recent role counts fully; one
     * last touched years ago counts less; one only listed in the skills line
     * (never mentioned in any role) counts 0.7 — real but unproven.
     */
    private double recencyFactor(String skill, JsonNode profile, YearMonth now) {
        double best = -1;
        for (JsonNode role : profile.path("experience")) {
            String haystack = (role.path("title").asText("") + " "
                    + role.path("description").asText("")).toLowerCase(Locale.ROOT);
            if (!haystack.contains(skill)) {
                continue; // this role doesn't mention the skill
            }
            double factor;
            if (role.path("current").asBoolean(false) || role.path("end_date").isNull()) {
                factor = 1.0; // using it right now
            } else {
                var end = Dates.parseYearMonth(role.path("end_date").asText(null));
                long yearsAgo = end.map(e -> (long) Math.max(0, now.getYear() - e.getYear())).orElse(99L);
                factor = yearsAgo <= 1 ? 1.0 : yearsAgo <= 3 ? 0.8 : yearsAgo <= 5 ? 0.6 : 0.4;
            }
            best = Math.max(best, factor);
        }
        return best < 0 ? 0.7 : best; // listed in skills but in no role text
    }

    /** Below the bar hurts fast; at the bar = full; mild damp for 3x+ overqualified. */
    private double experienceFit(int months, JobRequirements job, List<Evidence> evidence) {
        if (job.minExpMonths() <= 0 || job.fresherFriendly()) {
            evidence.add(new Evidence("experience", months + " months of experience (no bar for this job)", true));
            return months > 0 ? 1.0 : 0.8; // freshers aren't punished for being freshers
        }
        double ratio = (double) months / job.minExpMonths();
        evidence.add(new Evidence("experience", months + " months vs " + job.minExpMonths() + " required",
                ratio >= 1.0));
        if (ratio >= 3.0) {
            evidence.add(new Evidence("experience", "heavily overqualified — flight risk", false));
            return 0.8;
        }
        return Math.min(1.0, ratio); // linear up to the bar, flat after
    }

    /** Jaccard overlap between the job's words and each project's words. */
    private double projectRelevance(JsonNode profile, JobRequirements job, List<Evidence> evidence) {
        Set<String> jobTokens = tokens(String.join(" ", job.mustHave()) + " "
                + String.join(" ", job.niceToHave()));
        double best = 0;
        String bestName = null;
        for (JsonNode project : profile.path("projects")) {
            Set<String> projectTokens = tokens(project.path("name").asText("") + " "
                    + project.path("description").asText(""));
            if (projectTokens.isEmpty()) {
                continue;
            }
            Set<String> intersection = new HashSet<>(projectTokens);
            intersection.retainAll(jobTokens);
            Set<String> union = new HashSet<>(projectTokens);
            union.addAll(jobTokens);
            double jaccard = (double) intersection.size() / union.size();
            if (jaccard > best) {
                best = jaccard;
                bestName = project.path("name").asText("project");
            }
        }
        // Raw Jaccard over short texts is small — x4 rescales so "clearly
        // related project" lands near 1.0. (Embeddings replace this in Phase 7.)
        double score = Math.min(1.0, best * 4);
        if (bestName != null && score > 0.3) {
            evidence.add(new Evidence("project", "relevant project: " + bestName, true));
        }
        return score;
    }

    private double education(JsonNode profile, List<Evidence> evidence) {
        if (profile.path("education").size() == 0) {
            evidence.add(new Evidence("education", "no education listed", false));
            return 0.1;
        }
        JsonNode first = profile.path("education").get(0);
        boolean hasYear = first.path("year").isInt();
        evidence.add(new Evidence("education", first.path("degree").asText("degree")
                + (first.path("institution").isNull() ? "" : ", " + first.path("institution").asText("")), true));
        return hasYear ? 1.0 : 0.6; // a dated degree is verifiable; undated is weaker
    }

    private static Set<String> tokens(String text) {
        Set<String> stop = Set.of("and", "the", "with", "for", "using", "to", "of", "a", "in", "on");
        Set<String> result = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+")) {
            if (token.length() >= 2 && !stop.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
