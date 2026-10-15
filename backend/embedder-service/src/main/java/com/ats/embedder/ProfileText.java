package com.ats.embedder;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The text a profile is embedded from — the WHOLE profile, as the spec says. Measured on
 * the real data (2026-10-16): dropping the summary pushed the "event driven" semantic hit
 * from rank 3 to rank 9, so everything stays in.
 *
 * Example output:
 *   Backend Developer with 6 years of experience building python and aws systems. ...
 *   Skills: kafka, java, spring-boot, docker
 *   Backend Developer at Veritas Health: Optimized kafka queries and aws-based caching ...
 *   Project Inventory Sync Engine: Synced stock levels across warehouses with kafka ...
 */
final class ProfileText {

    static String of(JsonNode profile) {
        StringBuilder text = new StringBuilder();
        String summary = profile.path("summary").asText("");
        if (!summary.isBlank()) {
            text.append(summary).append('\n');
        }
        List<String> skills = new ArrayList<>();
        profile.path("skills").forEach(skill -> skills.add(skill.asText()));
        if (!skills.isEmpty()) {
            text.append("Skills: ").append(String.join(", ", skills)).append('\n');
        }
        for (JsonNode job : profile.path("experience")) {
            text.append(job.path("title").asText("")).append(" at ").append(job.path("company").asText(""))
                .append(": ").append(job.path("description").asText("")).append('\n');
        }
        for (JsonNode project : profile.path("projects")) {
            text.append("Project ").append(project.path("name").asText("")).append(": ")
                .append(project.path("description").asText("")).append('\n');
        }
        return text.toString();
    }

    private ProfileText() {
    }
}
