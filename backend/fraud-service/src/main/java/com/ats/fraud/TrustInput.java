package com.ats.fraud;

import java.time.YearMonth;

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
        YearMonth today           // "now" is passed in, so tests never depend on the real date
) {

    /** The "layout" block the parser writes into parsed.json. */
    public record HiddenTextStats(int totalChars, int tinyFontChars, int nearWhiteChars, String hiddenTextSample) {
    }
}
