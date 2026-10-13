package com.ats.fraud;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Check 3 — text a human can't see but a machine reads: font under 4pt and/or
 * white ink, counted by the parser while extracting (Phase 3 + the Batch 0 fix).
 * Keyword stuffing like this exists for one reason: to fool the matcher.
 */
@Component
public class HiddenTextCheck implements TrustCheck {

    static final double WEIGHT = 0.7; // the most deliberate trick we detect
    // A stray tiny page number or footer is not fraud; hundreds of hidden letters are.
    static final int MIN_HIDDEN_CHARS = 20;
    private static final int SAMPLE_SHOWN = 40;

    @Override
    public List<FraudFlag> check(TrustInput in) {
        TrustInput.HiddenTextStats layout = in.layout();
        if (layout == null) {
            return List.of(); // DOCX or image resume: no font fingerprint to judge
        }
        // tiny and white often describe the SAME letters (1pt white) — don't add them up
        int hidden = Math.max(layout.tinyFontChars(), layout.nearWhiteChars());
        if (hidden < MIN_HIDDEN_CHARS) {
            return List.of();
        }
        String how = layout.tinyFontChars() > 0 && layout.nearWhiteChars() > 0 ? "tiny white text"
                : layout.nearWhiteChars() > 0 ? "white text" : "text smaller than 4pt";
        String sample = layout.hiddenTextSample() == null ? "" : layout.hiddenTextSample();
        if (sample.length() > SAMPLE_SHOWN) {
            sample = sample.substring(0, SAMPLE_SHOWN) + "…";
        }
        return List.of(new FraudFlag("HIDDEN_TEXT", WEIGHT,
                "%d characters are invisible to a human reader (%s), e.g. \"%s\" — classic keyword stuffing."
                        .formatted(hidden, how, sample)));
    }
}
