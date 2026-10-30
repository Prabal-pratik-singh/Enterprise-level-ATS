package com.ats.candidate;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import com.ats.profile.EffectiveProfile;
import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The candidate's own fixes to their profile (C2): add or remove a skill, correct a role's dates. Stored
 * apart from what the extractor read, so a new resume never erases them; saving one announces
 * profile.changed (reason "edited") and the embedder refreshes the vector.
 */
@Service
public class ProfileOverridesService {

    /** The fixes document, the same shape that is stored and that GET returns. */
    public record Overrides(@JsonProperty("skills_added") List<String> skillsAdded,
                            @JsonProperty("skills_removed") List<String> skillsRemoved,
                            @JsonProperty("experience") Map<String, DateFix> experience) {
    }

    /** end_date: "YYYY-MM" or "present" (still working there). */
    public record DateFix(@JsonProperty("start_date") String startDate, @JsonProperty("end_date") String endDate) {
    }

    private static final Pattern YEAR_MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final int MAX_SKILLS = 50;
    private static final int MAX_ROLE_FIXES = 30;

    private final MasterProfileRepository profiles;
    private final SkillTaxonomy taxonomy;
    private final EffectiveProfile effectiveProfile;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public ProfileOverridesService(MasterProfileRepository profiles, SkillTaxonomy taxonomy,
                                   EffectiveProfile effectiveProfile, OutboxPublisher outbox, ObjectMapper mapper) {
        this.profiles = profiles;
        this.taxonomy = taxonomy;
        this.effectiveProfile = effectiveProfile;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void save(UUID userId, Overrides request) {
        MasterProfile profile = profiles.findById(userId).filter(p -> p.getProfile() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "upload a resume first — there is nothing to edit yet"));
        JsonNode extracted = read(profile.getProfile());
        YearMonth now = YearMonth.now();

        List<String> added = skills(request.skillsAdded());
        List<String> removed = new ArrayList<>(skills(request.skillsRemoved()));
        removed.removeAll(added);   // in both lists → she wants it: added wins

        ObjectNode doc = mapper.createObjectNode();
        added.forEach(doc.putArray("skills_added")::add);
        removed.forEach(doc.putArray("skills_removed")::add);
        ObjectNode experience = doc.putObject("experience");
        Map<String, DateFix> fixes = request.experience() == null ? Map.of() : request.experience();
        if (fixes.size() > MAX_ROLE_FIXES) {
            throw badRequest("too many role fixes (max " + MAX_ROLE_FIXES + ")");
        }
        fixes.forEach((rawKey, fix) -> {
            JsonNode role = roleFor(extracted, rawKey)
                    .orElseThrow(() -> badRequest("no role '" + rawKey + "' in your profile"));
            ObjectNode out = experience.putObject(EffectiveProfile.roleKey(role));
            checkDates(role, fix, now, out);
        });

        String overridesJson = doc.toString();
        double completeness = effectiveProfile.apply(extracted, doc, now).path("derived").path("completeness").asDouble();
        profile.replaceOverrides(overridesJson, completeness);
        profiles.save(profile);
        outbox.appendForSubject(Topics.PROFILE_CHANGED, EventEnvelope.PROFILE, userId, Map.of("reason", "edited"));
    }

    /** "Spring Boot" → "spring-boot" like the extractor and the job form; unknown spellings kept, lower-cased. */
    private List<String> skills(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String skill : raw == null ? List.<String>of() : raw) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            String trimmed = skill.trim();
            if (trimmed.length() > 60) {
                throw badRequest("a skill name is at most 60 characters");
            }
            out.add(taxonomy.canonical(trimmed).orElse(trimmed.toLowerCase(Locale.ROOT)));
        }
        if (out.size() > MAX_SKILLS) {
            throw badRequest("at most " + MAX_SKILLS + " skills per list");
        }
        return List.copyOf(out);
    }

    /** "Hyperloop Retail|Software Engineer" in any letter case → that role of the extracted profile. */
    private static Optional<JsonNode> roleFor(JsonNode extracted, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (JsonNode role : extracted.path("experience")) {
            String roleKey = EffectiveProfile.roleKey(role);
            if (seen.add(roleKey) && roleKey.equals(key)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /** YYYY-MM (end may be "present"), never in the future, start not after end — then write them into `out`. */
    private static void checkDates(JsonNode role, DateFix fix, YearMonth now, ObjectNode out) {
        if (fix == null || (fix.startDate() == null && fix.endDate() == null)) {
            throw badRequest("a role fix needs start_date or end_date");
        }
        YearMonth start = yearMonth(fix.startDate() != null ? fix.startDate() : role.path("start_date").asText(null),
                "start_date", now);
        boolean present = "present".equals(fix.endDate())
                || (fix.endDate() == null && (role.path("current").asBoolean(false) || role.path("end_date").isNull()));
        YearMonth end = present ? now
                : yearMonth(fix.endDate() != null ? fix.endDate() : role.path("end_date").asText(null), "end_date", now);
        if (start != null && end != null && start.isAfter(end)) {
            throw badRequest("start_date must not be after end_date");
        }
        if (fix.startDate() != null) {
            out.put("start_date", fix.startDate());
        }
        if (fix.endDate() != null) {
            out.put("end_date", fix.endDate());
        }
    }

    private static YearMonth yearMonth(String value, String field, YearMonth now) {
        if (value == null) {
            return null;   // the extracted role had none — nothing to compare
        }
        if (!YEAR_MONTH.matcher(value).matches()) {
            throw badRequest(field + " must look like 2024-06");
        }
        YearMonth parsed = YearMonth.parse(value);
        if (parsed.isAfter(now)) {
            throw badRequest(field + " cannot be in the future");
        }
        return parsed;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored profile JSON is unreadable", e);
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
