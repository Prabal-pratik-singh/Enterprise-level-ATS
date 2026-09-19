package com.ats.parser;

/**
 * Formatting fingerprint of one PDF, collected while extracting its text.
 * This is the evidence base for the Phase 6 hidden-text fraud heuristic:
 * honest resumes have ~0 tiny/near-white characters; keyword-stuffed ones
 * have hundreds.
 */
public record LayoutStats(
        int totalChars,         // all visible+invisible characters we saw
        int tinyFontChars,      // characters smaller than 4pt — unreadable by humans
        int nearWhiteChars,     // characters painted in (near-)white ink on white paper
        Float minFontSize,      // smallest font size seen anywhere (null = PDF had no text layer)
        String hiddenTextSample // first ~400 chars of the hidden text itself, as proof
) {
}
