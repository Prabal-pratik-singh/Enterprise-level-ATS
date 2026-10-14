package com.ats.dedup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The rules — pure Java, no database: given ONE new application and the earlier
 * applications that share its file, its contact details or an LSH bucket,
 * decide what each pair means.
 *
 *   same person (email or phone)  → REPEAT_APPLICANT  — a merge note, not fraud
 *   different person, same file   → DUPLICATE_FILE    — one document, two names
 *   different person, text ≥ 70%  → COPIED_CONTENT    — copied or shared work history
 */
public final class DuplicateFinder {

    // Measured on the real data (2026-10-15): the most similar HONEST pair shares 12%
    // of its work-history shingles. 70% leaves a wide margin and still catches a copy
    // with a few words changed.
    public static final double NEAR_DUPLICATE_THRESHOLD = 0.7;

    public enum Kind { REPEAT_APPLICANT, DUPLICATE_FILE, COPIED_CONTENT }

    /** One application as dedup sees it. clusterId/createdAt are only known for EARLIER ones. */
    public record Applicant(UUID applicationId, String name, String email, String phone,
                            String sha256, long[] signature, UUID clusterId, Instant createdAt) {
    }

    /** One finding about the pair (me, other). */
    public record Finding(Applicant other, Kind kind, double similarity, boolean sameFile) {
    }

    public record Result(List<Finding> findings, UUID clusterId) {
    }

    public static Result find(Applicant me, Collection<Applicant> others) {
        List<Finding> findings = new ArrayList<>();
        List<Applicant> sameContent = new ArrayList<>();
        for (Applicant other : others) {
            if (other.applicationId().equals(me.applicationId())) {
                continue;
            }
            boolean sameFile = me.sha256() != null && me.sha256().equals(other.sha256());
            double similarity = sameFile ? 1.0 : MinHash.similarity(me.signature(), other.signature());
            if (similarity >= NEAR_DUPLICATE_THRESHOLD) {
                sameContent.add(other); // clusters are about CONTENT, whoever sent it
            }
            if (samePerson(me, other)) {
                // re-applying (to another job, or with an updated resume) is normal
                findings.add(new Finding(other, Kind.REPEAT_APPLICANT, similarity, sameFile));
            } else if (sameFile) {
                findings.add(new Finding(other, Kind.DUPLICATE_FILE, 1.0, true));
            } else if (similarity >= NEAR_DUPLICATE_THRESHOLD) {
                findings.add(new Finding(other, Kind.COPIED_CONTENT, similarity, false));
            }
        }
        // join the cluster of the EARLIEST look-alike, so a cluster id never changes once given
        UUID cluster = sameContent.stream()
                .min(Comparator.comparing(Applicant::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Applicant::clusterId)
                .filter(Objects::nonNull)
                .orElse(me.applicationId());
        return new Result(findings, cluster);
    }

    /** The sentence a recruiter reads, written from `me`'s side of the pair. */
    public static String describe(Kind kind, double similarity, boolean sameFile, Applicant counterpart) {
        String who = "%s (%s)".formatted(counterpart.name(), counterpart.email());
        return switch (kind) {
            case REPEAT_APPLICANT -> "Same person as another application by %s%s — a merge note, not fraud."
                    .formatted(who, sameFile ? ", with the identical file" : "");
            case DUPLICATE_FILE -> "The identical resume file was also submitted by %s — one document under two names."
                    .formatted(who);
            case COPIED_CONTENT -> "%d%% of the work history matches the resume of %s — copied or shared."
                    .formatted(Math.round(similarity * 100), who);
        };
    }

    /** Same human = same email (case-insensitive) OR same phone (last 10 digits). */
    static boolean samePerson(Applicant a, Applicant b) {
        String emailA = normalizeEmail(a.email());
        String phoneA = normalizePhone(a.phone());
        return (emailA != null && emailA.equals(normalizeEmail(b.email())))
                || (phoneA != null && phoneA.equals(normalizePhone(b.phone())));
    }

    public static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** "+91-98765 43210" and "9876543210" are the same phone: keep the last 10 digits. */
    public static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return null; // too short to identify anyone
        }
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private DuplicateFinder() {
    }
}
