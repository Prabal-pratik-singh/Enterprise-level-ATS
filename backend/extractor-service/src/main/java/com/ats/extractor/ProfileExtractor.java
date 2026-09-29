package com.ats.extractor;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ats.llm.LlmClient;
import com.ats.taxonomy.SkillTaxonomy;
import com.ats.taxonomy.UnmatchedSkills;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns raw resume text into a validated, normalized profile:
 *   1. ask the LLM (system prompt carries the injection defense)
 *   2. schema-validate; invalid -> ONE corrective retry with the errors shown
 *   3. regex fallback: missing email/phone recovered from the raw text
 *   4. skills normalized via the taxonomy; unknown ones logged, not dropped
 *   5. derived facts (months, seniority, completeness) computed by OUR math
 * Still failing after the retry -> exception -> Kafka retries -> DLQ.
 */
@Component
public class ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractor.class);

    // Guard the model's context window; resumes are ~2-6k chars anyway
    private static final int MAX_TEXT_CHARS = 15_000;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s()\\-]{8,}\\d");

    private final LlmClient llm;
    private final ProfileValidator validator;
    private final SkillTaxonomy taxonomy;
    private final UnmatchedSkills unmatchedSkills;
    private final ExperienceMath math;
    private final ObjectMapper mapper;

    public ProfileExtractor(LlmClient llm, ProfileValidator validator, SkillTaxonomy taxonomy,
                            UnmatchedSkills unmatchedSkills, ExperienceMath math, ObjectMapper mapper) {
        this.llm = llm;
        this.validator = validator;
        this.taxonomy = taxonomy;
        this.unmatchedSkills = unmatchedSkills;
        this.math = math;
        this.mapper = mapper;
    }

    /** What the recorder needs: the JSON plus the numbers that go in columns/events. */
    public record ExtractedProfile(ObjectNode profile, double completeness,
                                   int totalExperienceMonths, int skillsCount) {
    }

    public ExtractedProfile extract(String resumeText) {
        String text = resumeText.length() > MAX_TEXT_CHARS
                ? resumeText.substring(0, MAX_TEXT_CHARS) : resumeText;

        ObjectNode profile = askWithOneRetry(text);

        fillContactFallback(profile, text);   // the regex net under the LLM
        normalizeSkills(profile);             // canonical spellings + unknown logging

        // Derived facts are OUR arithmetic — the LLM only supplied the dates
        int months = math.totalExperienceMonths(profile.path("experience"), YearMonth.now());
        double completeness = math.completeness(profile);
        ObjectNode derived = profile.putObject("derived");
        derived.put("total_experience_months", months);
        derived.put("seniority", math.seniority(months));
        derived.put("completeness", completeness);

        return new ExtractedProfile(profile, completeness, months, profile.path("skills").size());
    }

    /** One honest question, one corrective retry, then give up loudly. */
    private ObjectNode askWithOneRetry(String text) {
        String first = llm.complete(ProfilePrompts.SYSTEM, ProfilePrompts.userPrompt(text), validator.schemaJson());
        Attempt attempt = judge(first);
        if (attempt.valid()) {
            return attempt.profile();
        }
        log.info("LLM answer failed validation ({}), retrying once", attempt.errors());
        String second = llm.complete(ProfilePrompts.SYSTEM,
                ProfilePrompts.retryPrompt(text, first, attempt.errors()), validator.schemaJson());
        Attempt retry = judge(second);
        if (retry.valid()) {
            return retry.profile();
        }
        // -> Kafka retry policy -> resume.parsed.dlq; a human sees it in Kafka UI
        throw new IllegalStateException("LLM produced an invalid profile twice: " + retry.errors());
    }

    private record Attempt(ObjectNode profile, String errors) {
        boolean valid() {
            return errors == null;
        }
    }

    /** Parse + schema-check one LLM answer; return either the profile or the reasons. */
    private Attempt judge(String llmAnswer) {
        try {
            JsonNode node = mapper.readTree(llmAnswer);
            if (!(node instanceof ObjectNode obj)) {
                return new Attempt(null, "the answer is not a JSON object");
            }
            obj = unwrapIfNested(obj); // forgive {"CandidateProfile": {...}} wrapping
            if (obj.has("properties") || obj.has("$schema")) {
                // The model parroted the schema back — tell it exactly that on retry
                return new Attempt(null, "you returned the schema itself — return the extracted profile DATA instead");
            }
            var errors = validator.validate(obj);
            return errors.isEmpty() ? new Attempt(obj, null) : new Attempt(null, String.join("; ", errors));
        } catch (JsonProcessingException e) {
            return new Attempt(null, "not parseable JSON: " + e.getOriginalMessage());
        }
    }

    /** Small models sometimes wrap the profile in a single-key envelope. Unwrap one level. */
    private static ObjectNode unwrapIfNested(ObjectNode obj) {
        if (!obj.has("contact") && obj.size() == 1) {
            JsonNode only = obj.elements().next();
            if (only instanceof ObjectNode inner && inner.has("contact")) {
                return inner;
            }
        }
        return obj;
    }

    /** LLM missed the email/phone? Plain regex over the raw text catches most. */
    private void fillContactFallback(ObjectNode profile, String rawText) {
        ObjectNode contact = profile.has("contact") && profile.get("contact").isObject()
                ? (ObjectNode) profile.get("contact") : profile.putObject("contact");
        if (!hasText(contact.path("email"))) {
            Matcher m = EMAIL.matcher(rawText);
            if (m.find()) {
                contact.put("email", m.group());
            }
        }
        if (!hasText(contact.path("phone"))) {
            Matcher m = PHONE.matcher(rawText);
            if (m.find()) {
                contact.put("phone", m.group().trim());
            }
        }
    }

    /** "ReactJS" -> "react"; unknown skills are kept AND logged for taxonomy growth. */
    private void normalizeSkills(ObjectNode profile) {
        Set<String> normalized = new LinkedHashSet<>(); // preserves order, kills duplicates
        for (JsonNode skillNode : profile.path("skills")) {
            String raw = skillNode.asText("");
            var canonical = taxonomy.canonical(raw);
            if (canonical.isPresent()) {
                normalized.add(canonical.get());
            } else if (!raw.isBlank()) {
                String kept = raw.trim().toLowerCase();
                unmatchedSkills.record(kept); // the taxonomy's to-do list
                normalized.add(kept);         // still a skill — unknown ≠ worthless
            }
        }
        ArrayNode skills = profile.putArray("skills");
        normalized.forEach(skills::add);
    }

    private static boolean hasText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }
}
