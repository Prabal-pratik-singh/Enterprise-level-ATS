package com.ats.fraud;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ats.taxonomy.SkillTaxonomy;
import org.springframework.stereotype.Component;

/**
 * Check 2 — technology used before it existed. skills.json knows the year each
 * technology was first released (Kubernetes 2014, Kafka 2011, ...), so:
 *  a) a job that ENDED in 2012 cannot have used Kubernetes, and
 *  b) "15 years of Kubernetes" written in 2026 means since 2011 — impossible.
 * (A job that merely STARTED earlier is fine: it may have adopted the tool later.)
 */
@Component
public class AnachronismCheck implements TrustCheck {

    static final double WEIGHT = 0.6;
    // "go", "r", "c", "ts" are also everyday words/letters ("go live") — too short to trust in free text
    private static final int MIN_WORD_LENGTH = 3;

    private final SkillTaxonomy taxonomy;

    public AnachronismCheck(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public List<FraudFlag> check(TrustInput in) {
        Map<String, FraudFlag> bySkill = new LinkedHashMap<>(); // one flag per technology is enough

        for (Roles.Role role : Roles.parse(in.profile(), in.today())) {
            for (String skill : skillsMentioned(role.description())) {
                Optional<Integer> released = taxonomy.firstReleaseYear(skill);
                if (released.isPresent() && role.end().getYear() < released.get()) {
                    bySkill.putIfAbsent(skill, new FraudFlag("TECH_ANACHRONISM", WEIGHT,
                            "Says %s was used as %s, but %s was first released in %d."
                                    .formatted(skill, role.label(), skill, released.get())));
                }
            }
        }

        String summary = in.profile().path("summary").asText(null);
        for (ExperienceClaims.Claim claim : ExperienceClaims.find(summary, taxonomy)) {
            if (claim.skill() == null) {
                continue; // general claims are the timeline check's business
            }
            Optional<Integer> released = taxonomy.firstReleaseYear(claim.skill());
            int since = in.today().getYear() - claim.years();
            if (released.isPresent() && since < released.get()) {
                bySkill.putIfAbsent(claim.skill(), new FraudFlag("TECH_ANACHRONISM", WEIGHT,
                        "Claims %d years of %s (since %d), but %s was first released in %d."
                                .formatted(claim.years(), claim.skill(), since, claim.skill(), released.get())));
            }
        }
        return List.copyOf(bySkill.values());
    }

    /** Canonical skills named in free text: "kafka-based", "spring-boot" and "Spring Boot" all count. */
    Set<String> skillsMentioned(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        String[] words = text.split("[^A-Za-z0-9.+#-]+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i].replaceAll("^[.-]+|[.-]+$", ""); // trim sentence punctuation
            addIfSkill(found, word);
            String[] parts = word.split("-");
            for (int p = 0; p < parts.length; p++) {
                addIfSkill(found, parts[p]);                                // "kafka-based" -> "kafka"
                if (p + 1 < parts.length) {
                    addIfSkill(found, parts[p] + "-" + parts[p + 1]);       // "spring-boot-based" -> "spring-boot"
                }
            }
            if (i + 1 < words.length) {
                addIfSkill(found, word + " " + words[i + 1]);               // "Spring Boot", "Apache Kafka"
            }
        }
        return found;
    }

    private void addIfSkill(Set<String> found, String candidate) {
        if (candidate.length() >= MIN_WORD_LENGTH) {
            taxonomy.canonical(candidate).ifPresent(found::add);
        }
    }
}
