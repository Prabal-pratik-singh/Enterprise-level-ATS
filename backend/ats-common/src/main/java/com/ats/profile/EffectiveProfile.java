package com.ats.profile;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * A candidate's profile as everyone should see it: what the extractor read from the newest resume, with
 * the candidate's own fixes on top. One function, used by the API (what the page shows), the embedder
 * (what the vector says) and later role fit (C3) and feedback (C4) — so no part of the system reads a
 * different profile.
 *
 * Overrides: {"skills_added": ["kafka"], "skills_removed": ["html"],
 *             "experience": {"hyperloop retail|software engineer": {"end_date": "2024-06"}}}
 * Date fixes are keyed by company + title (lower-case), not by position, so they survive a re-parse that
 * lists the roles in another order. end_date "present" means "I still work here".
 */
@Component
public class EffectiveProfile {

    private final ExperienceMath math;

    public EffectiveProfile(ExperienceMath math) {
        this.math = math;
    }

    public ObjectNode apply(JsonNode extracted, JsonNode overrides, YearMonth now) {
        ObjectNode profile = extracted.deepCopy();

        // skills: what was read, minus what she removed, plus what she added — each one once
        Set<String> skills = new LinkedHashSet<>();
        profile.path("skills").forEach(skill -> skills.add(skill.asText()));
        overrides.path("skills_removed").forEach(skill -> skills.remove(skill.asText()));
        overrides.path("skills_added").forEach(skill -> skills.add(skill.asText()));
        ArrayNode skillArray = profile.putArray("skills");
        skills.forEach(skillArray::add);

        // date fixes, found by company + title
        for (JsonNode role : profile.path("experience")) {
            JsonNode fix = overrides.path("experience").path(roleKey(role));
            if (role.isObject() && fix.isObject()) {
                applyDates((ObjectNode) role, fix);
            }
        }

        // derived facts follow the fixed dates, exactly as the extractor computes them
        ObjectNode derived = profile.get("derived") instanceof ObjectNode existing ? existing : profile.putObject("derived");
        int months = math.totalExperienceMonths(profile.path("experience"), now);
        derived.put("total_experience_months", months);
        derived.put("seniority", math.seniority(months));
        derived.put("completeness", math.completeness(profile));
        return profile;
    }

    /** "Hyperloop Retail" + "Software Engineer" → "hyperloop retail|software engineer". */
    public static String roleKey(String company, String title) {
        return (trim(company) + "|" + trim(title)).toLowerCase(Locale.ROOT);
    }

    public static String roleKey(JsonNode role) {
        return roleKey(role.path("company").asText(""), role.path("title").asText(""));
    }

    private static void applyDates(ObjectNode role, JsonNode fix) {
        if (fix.hasNonNull("start_date")) {
            role.put("start_date", fix.get("start_date").asText());
        }
        if (fix.hasNonNull("end_date")) {
            String end = fix.get("end_date").asText();
            if ("present".equals(end)) {
                role.putNull("end_date");
                role.put("current", true);
            } else {
                role.put("end_date", end);
                role.put("current", false);
            }
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
