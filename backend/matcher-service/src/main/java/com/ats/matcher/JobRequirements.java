package com.ats.matcher;

import java.util.ArrayList;
import java.util.List;

import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The job's requirements JSON, parsed and normalized once per scoring run.
 * Shape in the jobs table:
 * {"must_have":[...], "nice_to_have":[...], "min_exp_months":24,
 *  "fresher_friendly":false, "weights":"default"}
 */
public record JobRequirements(
        List<String> mustHave,      // canonical skill names — non-negotiable
        List<String> niceToHave,    // canonical skill names — bonus points
        int minExpMonths,           // experience bar (ignored for fresher-friendly jobs)
        boolean fresherFriendly,    // flips the weight profile below
        Weights weights) {

    /** How much each of the six components counts. Always sums to 1.0. */
    public record Weights(double skillMatch, double experienceFit, double projectRelevance,
                          double education, double certifications, double resumeQuality) {
    }

    // Standard job: skills and experience dominate.
    static final Weights DEFAULT_WEIGHTS = new Weights(0.35, 0.25, 0.15, 0.10, 0.05, 0.10);

    // Fresher-friendly job: experience nearly vanishes (0.05) and its weight
    // flows to projects, education and resume quality — a strong student
    // project portfolio can now beat a mediocre year of experience.
    static final Weights FRESHER_WEIGHTS = new Weights(0.35, 0.05, 0.25, 0.15, 0.08, 0.12);

    /** Parse the jobs.requirements JSONB; skill names are taxonomy-normalized. */
    public static JobRequirements parse(JsonNode json, SkillTaxonomy taxonomy) {
        boolean fresher = json.path("fresher_friendly").asBoolean(false);
        return new JobRequirements(
                normalize(json.path("must_have"), taxonomy),
                normalize(json.path("nice_to_have"), taxonomy),
                json.path("min_exp_months").asInt(0),
                fresher,
                fresher ? FRESHER_WEIGHTS : DEFAULT_WEIGHTS); // "weights":"default" = pick by flag
    }

    private static List<String> normalize(JsonNode array, SkillTaxonomy taxonomy) {
        List<String> result = new ArrayList<>();
        for (JsonNode skill : array) {
            String raw = skill.asText("");
            // Canonical name when known, cleaned-up raw otherwise — same rule
            // the extractor used, so both sides speak the same vocabulary.
            result.add(taxonomy.canonical(raw).orElse(raw.trim().toLowerCase()));
        }
        return result;
    }
}
