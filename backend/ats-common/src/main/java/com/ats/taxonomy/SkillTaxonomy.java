package com.ats.taxonomy;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * The skill dictionary (resources/taxonomy/skills.json): ~150 canonical skills,
 * their aliases, and the year each tech first existed. Used by the extractor
 * (normalize LLM output), the matcher (compare against job requirements) and
 * the fraud service (anachronism check: "10 years of Kubernetes" before 2014+10
 * is impossible).
 */
@Component
public class SkillTaxonomy {

    /** One entry of skills.json. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(@JsonProperty("name") String name,
                 @JsonProperty("aliases") List<String> aliases,
                 @JsonProperty("first_release_year") Integer firstReleaseYear) {
    }

    private final Map<String, String> aliasToCanonical = new HashMap<>(); // normalized spelling -> canonical name
    private final Map<String, Integer> releaseYears = new HashMap<>();    // canonical name -> first year it existed

    public SkillTaxonomy(ObjectMapper mapper) {
        try (InputStream in = getClass().getResourceAsStream("/taxonomy/skills.json")) {
            List<Entry> entries = mapper.readValue(in, new TypeReference<List<Entry>>() {
            });
            for (Entry entry : entries) {
                // The canonical name is also a valid spelling of itself
                aliasToCanonical.put(normalize(entry.name()), entry.name());
                if (entry.aliases() != null) {
                    for (String alias : entry.aliases()) {
                        aliasToCanonical.put(normalize(alias), entry.name());
                    }
                }
                if (entry.firstReleaseYear() != null) {
                    releaseYears.put(entry.name(), entry.firstReleaseYear());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load skills taxonomy", e);
        }
    }

    /** "ReactJS" → "react"; "Spring Boot" → "spring-boot"; unknown → empty. */
    public Optional<String> canonical(String rawSkill) {
        if (rawSkill == null || rawSkill.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(aliasToCanonical.get(normalize(rawSkill)));
    }

    /** Year this tech first existed — the fraud service's anachronism reference. */
    public Optional<Integer> firstReleaseYear(String canonicalName) {
        return Optional.ofNullable(releaseYears.get(canonicalName));
    }

    /**
     * Collapse spelling differences: lowercase + drop everything that isn't a
     * letter or digit. "Node.JS", "node js" and "NodeJS" all become "nodejs".
     */
    static String normalize(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
