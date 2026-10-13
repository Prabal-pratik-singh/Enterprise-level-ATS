package com.ats.fraud;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ats.taxonomy.SkillTaxonomy;

/**
 * Finds "N years" claims in free text. A claim is about ONE SKILL only when the
 * skill comes before the word "experience":
 *   "8+ years of hands-on production Kubernetes experience" -> 8 years of kubernetes
 *   "9 years of experience building git and mongodb systems" -> 9 years, general
 * (pinning those 9 years on git would be wrong — they describe the work, not the claim).
 */
final class ExperienceClaims {

    /** years = the number claimed; skill = canonical skill name, or null for a general claim. */
    record Claim(int years, String skill) {
    }

    // "8 years", "8+ years", "10 yrs" — plus the next few words, which we inspect one by one
    private static final Pattern CLAIM = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\b((?:\\s+[A-Za-z][\\w.+#-]*){0,6})",
            Pattern.CASE_INSENSITIVE);

    static List<Claim> find(String text, SkillTaxonomy taxonomy) {
        List<Claim> claims = new ArrayList<>();
        if (text == null) {
            return claims;
        }
        Matcher m = CLAIM.matcher(text);
        while (m.find()) {
            String skill = null;
            for (String raw : m.group(2).trim().split("\\s+")) {
                String word = raw.replaceAll("[.,;:]+$", ""); // "Kubernetes," -> "Kubernetes"
                if (word.equalsIgnoreCase("experience")) {
                    break; // skills named after this describe the work, not the claim
                }
                Optional<String> canonical = taxonomy.canonical(word);
                if (canonical.isPresent()) {
                    skill = canonical.get();
                    break;
                }
            }
            claims.add(new Claim(Integer.parseInt(m.group(1)), skill));
        }
        return claims;
    }

    private ExperienceClaims() {
    }
}
