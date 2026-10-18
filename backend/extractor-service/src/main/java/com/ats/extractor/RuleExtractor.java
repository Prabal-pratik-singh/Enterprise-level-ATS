package com.ats.extractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Load-test extraction: reads the synthetic generator's FIXED layout with plain rules —
 * ~50ms, no LLM, no quota. Used ONLY for applications marked load_test (seed.sh --bulk);
 * every real resume goes to the LLM. Free-tier Groq allows 1,000 calls/day — measured
 * 2026-10-17 — so 10,000 resumes through the LLM would take 10+ days.
 *
 *   line 1                          → contact.name
 *   line 2  "email | phone | city"  → contact.email / phone / location
 *   UPPER-CASE headings             → sections
 *   "Title — Company  (Jul 2024 - Present, Full-time)" + "- bullets" → one experience entry
 *   "Degree, School — graduated 2016"                                → one education entry
 */
@Component
public class RuleExtractor {

    private static final Pattern ROLE = Pattern.compile(
            "^(.+?)\\s+—\\s+(.+?)\\s+\\((\\w{3} \\d{4})\\s*-\\s*(\\w{3} \\d{4}|Present)[^)]*\\)\\s*$");
    private static final Pattern DEGREE = Pattern.compile("^(.+?),\\s*(.+?)\\s+—\\s+graduated\\s+(\\d{4})\\s*$");
    private static final Set<String> HEADINGS = Set.of("SUMMARY", "SKILLS", "EXPERIENCE", "EDUCATION",
            "PROJECTS", "CERTIFICATIONS");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec");

    private final ObjectMapper mapper;

    public RuleExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode extract(String text) {
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() < 3) {
            throw new IllegalStateException("rule extraction: resume too short to be the synthetic layout");
        }
        ObjectNode profile = mapper.createObjectNode();
        ObjectNode contact = profile.putObject("contact");
        contact.put("name", lines.get(0));
        String[] parts = lines.get(1).split("\\|");                    // email | phone | city
        contact.put("email", part(parts, 0));
        contact.put("phone", part(parts, 1));
        contact.put("location", part(parts, 2));

        Map<String, List<String>> sections = sections(lines.subList(2, lines.size()));
        if (!sections.containsKey("EXPERIENCE") || !sections.containsKey("SKILLS")) {
            // not our layout (or OCR mangled a heading) — fail loudly; NEVER fall back to the LLM here
            throw new IllegalStateException(
                    "rule extraction: missing SKILLS/EXPERIENCE headings — not the synthetic layout");
        }
        profile.put("summary", String.join(" ", sections.getOrDefault("SUMMARY", List.of())));
        ArrayNode skills = profile.putArray("skills");
        for (String line : sections.get("SKILLS")) {
            Arrays.stream(line.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(skills::add);
        }
        profile.set("experience", experience(sections.get("EXPERIENCE")));
        profile.set("education", education(sections.getOrDefault("EDUCATION", List.of())));
        profile.set("projects", projects(sections.getOrDefault("PROJECTS", List.of())));
        profile.set("certifications", bullets(sections.getOrDefault("CERTIFICATIONS", List.of())));
        return profile;
    }

    /** Group lines under their UPPER-CASE heading, in order. */
    private static Map<String, List<String>> sections(List<String> lines) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : lines) {
            String heading = line.toUpperCase(Locale.ROOT);
            if (HEADINGS.contains(heading)) {
                current = sections.computeIfAbsent(heading, k -> new ArrayList<>());
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }

    /** A ROLE line opens a job; following "- " bullets (and wrapped lines) become its description. */
    private ArrayNode experience(List<String> lines) {
        ArrayNode jobs = mapper.createArrayNode();
        ObjectNode job = null;
        StringBuilder description = new StringBuilder();
        for (String line : lines) {
            Matcher m = ROLE.matcher(line);
            if (m.matches()) {
                finish(job, description);
                job = jobs.addObject();
                job.put("title", m.group(1).strip());
                job.put("company", m.group(2).strip());
                job.put("start_date", yearMonth(m.group(3)));
                String end = yearMonth(m.group(4));
                job.put("end_date", end);                   // null = still there
                job.put("current", end == null);
                description = new StringBuilder();
            } else if (job != null) {
                description.append(description.isEmpty() ? "" : " ").append(stripBullet(line));
            }
        }
        finish(job, description);
        return jobs;
    }

    private static void finish(ObjectNode job, StringBuilder description) {
        if (job != null) {
            job.put("description", description.toString());
        }
    }

    private ArrayNode education(List<String> lines) {
        ArrayNode education = mapper.createArrayNode();
        for (String line : lines) {
            Matcher m = DEGREE.matcher(line);
            if (m.matches()) {
                ObjectNode entry = education.addObject();
                entry.put("degree", m.group(1).strip());
                entry.put("institution", m.group(2).strip());
                entry.put("year", Integer.parseInt(m.group(3)));
            }
        }
        return education;
    }

    /** A plain line names a project; the "- " line(s) after it describe it. */
    private ArrayNode projects(List<String> lines) {
        ArrayNode projects = mapper.createArrayNode();
        ObjectNode project = null;
        for (String line : lines) {
            if (!line.startsWith("-")) {
                project = projects.addObject();
                project.put("name", line);
                project.put("description", "");
            } else if (project != null) {
                String before = project.path("description").asText();
                project.put("description", (before.isEmpty() ? "" : before + " ") + stripBullet(line));
            }
        }
        return projects;
    }

    private ArrayNode bullets(List<String> lines) {
        ArrayNode out = mapper.createArrayNode();
        lines.stream().map(RuleExtractor::stripBullet).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    /** "Jul 2024" → "2024-07"; "Present" (or anything unreadable) → null. */
    static String yearMonth(String text) {
        String[] parts = text.strip().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        int month = MONTHS.indexOf(parts[0].toLowerCase(Locale.ROOT)) + 1;
        if (month == 0 || !parts[1].matches("\\d{4}")) {
            return null;
        }
        return "%s-%02d".formatted(parts[1], month);
    }

    private static String stripBullet(String line) {
        return line.replaceFirst("^-\\s*", "").strip();
    }

    private static String part(String[] parts, int index) {
        if (index >= parts.length) {
            return null;
        }
        String value = parts[index].strip();
        return value.isEmpty() ? null : value;
    }
}
