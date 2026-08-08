package com.ats.domain;

/**
 * Application state machine:
 * APPLIED → PARSED → EXTRACTED → SCORED
 * (+ FLAGGED_FOR_REVIEW, SHORTLISTED, REJECTED, PARSE_FAILED)
 * Every transition appends a row to application_events.
 */
public enum ApplicationStatus {
    APPLIED,
    PARSED,
    EXTRACTED,
    SCORED,
    FLAGGED_FOR_REVIEW,
    SHORTLISTED,
    REJECTED,
    PARSE_FAILED
}
