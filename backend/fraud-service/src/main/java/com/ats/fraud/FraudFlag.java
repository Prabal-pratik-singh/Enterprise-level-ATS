package com.ats.fraud;

/** One reason to distrust a resume — written for a recruiter to read, not a developer. */
public record FraudFlag(
        String code,        // stable machine name, e.g. HIDDEN_TEXT (the UI can pick an icon by it)
        double weight,      // 0..1 — how strongly this signal ALONE suggests fraud
        String explanation  // plain English, shown word-for-word in the dashboard
) {
}
