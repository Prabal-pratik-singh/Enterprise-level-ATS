package com.ats.parser;

import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * A PDFTextStripper that extracts the text like normal, but also inspects
 * EVERY character's font size and ink color on the way through. This is how
 * hidden white 1pt keyword-stuffing gets caught: invisible to a recruiter,
 * loud and clear in these counters.
 */
class LayoutCapturingStripper extends PDFTextStripper {

    // Thresholds from the design: <4pt is unreadable; RGB channels all >0.92 is
    // effectively white ink on white paper.
    private static final float TINY_FONT_PT = 4f;
    private static final float NEAR_WHITE_CHANNEL = 0.92f;
    private static final int SAMPLE_LIMIT = 400; // keep only a taste of the hidden text, not all of it

    private int totalChars;
    private int tinyFontChars;
    private int nearWhiteChars;
    private float minFontSize = Float.MAX_VALUE;
    private final StringBuilder hiddenSample = new StringBuilder();

    LayoutCapturingStripper() {
        setSortByPosition(true); // read in visual order (top-to-bottom), not internal file order
    }

    /** PDFBox calls this once per character it extracts — our inspection hook. */
    @Override
    protected void processTextPosition(TextPosition character) {
        String unicode = character.getUnicode();
        if (unicode != null && !unicode.isBlank()) { // ignore spacing — only count real, visible glyphs
            totalChars++;

            float size = character.getFontSizeInPt(); // font size in printer's points (normal body text ≈ 9-12pt)
            if (size > 0) {
                minFontSize = Math.min(minFontSize, size);
            }

            boolean tiny = size > 0 && size < TINY_FONT_PT;
            boolean white = paintedNearWhite();
            if (tiny) {
                tinyFontChars++;
            }
            if (white) {
                nearWhiteChars++;
            }
            // Keep a sample of the hidden text itself as human-readable proof
            if ((tiny || white) && hiddenSample.length() < SAMPLE_LIMIT) {
                hiddenSample.append(unicode);
            }
        }
        super.processTextPosition(character); // IMPORTANT: let PDFBox keep building the normal text
    }

    /** Is the CURRENT ink color close enough to white to be invisible on paper? */
    private boolean paintedNearWhite() {
        try {
            // "Non-stroking color" = the fill color used to paint glyphs
            PDColor color = getGraphicsState().getNonStrokingColor();
            float[] rgb = color.getColorSpace().toRGB(color.getComponents()); // normalize any color space to [r,g,b] 0..1
            return rgb[0] > NEAR_WHITE_CHANNEL && rgb[1] > NEAR_WHITE_CHANNEL && rgb[2] > NEAR_WHITE_CHANNEL;
        } catch (Exception e) {
            // Exotic color spaces (patterns, separations) can refuse to convert —
            // treat as "not white" rather than fail the whole parse over ink color.
            return false;
        }
    }

    /** Snapshot of everything we observed, taken after getText() has run. */
    LayoutStats stats() {
        return new LayoutStats(
                totalChars,
                tinyFontChars,
                nearWhiteChars,
                totalChars == 0 ? null : minFontSize, // no text at all -> no meaningful minimum
                hiddenSample.toString());
    }
}
