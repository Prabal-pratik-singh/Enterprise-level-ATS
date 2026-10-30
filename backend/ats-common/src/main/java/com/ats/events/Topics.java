package com.ats.events;

import java.util.List;

/**
 * Kafka topic catalog. Every topic has 12 partitions, is keyed by
 * applicationId (the profile.* topics by the candidate's account id), and carries
 * S3 keys/ids in payloads — never file bytes.
 * Each topic has a ".dlq" twin for poison messages.
 */
public final class Topics {

    public static final String APPLICATION_CREATED = "application.created";
    public static final String RESUME_UPLOADED     = "resume.uploaded";
    public static final String RESUME_PARSED       = "resume.parsed";
    public static final String RESUME_EXTRACTED    = "resume.extracted";
    public static final String RESUME_EMBEDDED     = "resume.embedded";
    public static final String APPLICATION_DEDUPED = "application.deduped";
    public static final String APPLICATION_FLAGGED = "application.flagged";
    public static final String APPLICATION_SCORED  = "application.scored";
    public static final String SHORTLIST_UPDATED   = "shortlist.updated";

    // Part 2: a candidate's own profile resume runs through the same workers on topics of its own,
    // so the application-only readers (matcher, fraud, dedup, indexer) never see a profile event.
    // Keyed by the candidate's account id.
    public static final String PROFILE_RESUME_UPLOADED = "profile.resume.uploaded"; // api → parser
    public static final String PROFILE_RESUME_PARSED   = "profile.resume.parsed";   // parser → extractor
    public static final String PROFILE_CHANGED         = "profile.changed";         // extractor / an edit → embedder
    public static final String PROFILE_EMBEDDED        = "profile.embedded";        // embedder → role classifier (C3)

    public static final List<String> ALL = List.of(
            APPLICATION_CREATED,
            RESUME_UPLOADED,
            RESUME_PARSED,
            RESUME_EXTRACTED,
            RESUME_EMBEDDED,
            APPLICATION_DEDUPED,
            APPLICATION_FLAGGED,
            APPLICATION_SCORED,
            SHORTLIST_UPDATED,
            PROFILE_RESUME_UPLOADED,
            PROFILE_RESUME_PARSED,
            PROFILE_CHANGED,
            PROFILE_EMBEDDED);

    public static String dlq(String topic) {
        return topic + ".dlq";
    }

    private Topics() {
    }
}
