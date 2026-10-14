package com.ats.fraud;

import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything the trust checks look at for one application. The checks are pure
 * functions of this record — no database, no Kafka, no clock — which is what
 * makes each of them testable in a few lines.
 */
public record TrustInput(
        JsonNode profile,         // the extractor's structured profile (experience, education, summary, ...)
        String applicantEmail,    // the email they applied with
        HiddenTextStats layout,   // the parser's font/ink fingerprint; null when there is none (DOCX, images)
        YearMonth today,          // "now" is passed in, so tests never depend on the real date
        List<DuplicateMatch> duplicates // from dedup's duplicate_matches table (empty until dedup has run)
) {

    /** Without dedup findings — what every check except DuplicateCheck needs. */
    public TrustInput(JsonNode profile, String applicantEmail, HiddenTextStats layout, YearMonth today) {
        this(profile, applicantEmail, layout, today, List.of());
    }

    /** The "layout" block the parser writes into parsed.json. */
    public record HiddenTextStats(int totalChars, int tinyFontChars, int nearWhiteChars, String hiddenTextSample) {
    }

    /** One row of duplicate_matches, as the Dedup Service wrote it. */
    public record DuplicateMatch(String kind, double similarity, String explanation) {
    }
}
