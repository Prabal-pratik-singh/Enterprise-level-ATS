package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "candidates")
public class Candidate {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "identity_source", nullable = false)
    private String identitySource;   // form | resume_pending | resume (V7)

    // V10: the account this row belongs to, set only by a verified account claim (a native UPDATE in
    // CandidateRepository). Read-only here on purpose: the extractor saves whole Candidate rows and
    // would otherwise write back a stale NULL over a claim made a moment earlier.
    @Column(name = "user_id", insertable = false, updatable = false)
    private UUID userId;

    /** Placeholder emails end here: ".invalid" is reserved and can never be a real, reachable address. */
    public static final String PENDING_EMAIL_DOMAIN = "@intake.invalid";

    protected Candidate() {
    }

    public Candidate(String fullName, String email, String phone) {
        this.id = UUID.randomUUID();
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.createdAt = Instant.now();
        this.identitySource = "form";   // typed in — never overwritten by the extractor
    }

    /** Bulk upload: a placeholder identity, replaced by the extractor once the resume is read. */
    public static Candidate pendingFromFile(String filename) {
        Candidate c = new Candidate(displayNameOf(filename),
                "pending+" + UUID.randomUUID().toString().substring(0, 8) + PENDING_EMAIL_DOMAIN, null);
        c.identitySource = "resume_pending";
        return c;
    }

    /** The extractor read the resume: keep what it found, keep the placeholder for what it didn't. */
    public void adoptIdentityFromResume(String name, String email, String phone) {
        if (name != null && !name.isBlank()) {
            this.fullName = name.trim();
        }
        if (email != null && email.contains("@")) {
            this.email = email.trim();
        }
        if (phone != null && !phone.isBlank()) {
            this.phone = phone.trim();
        }
        this.identitySource = "resume";
    }

    public boolean isIdentityPending() {
        return "resume_pending".equals(identitySource);
    }

    /** "cv_final-v3.pdf" → "cv final v3" — readable until the real name arrives. */
    static String displayNameOf(String filename) {
        String base = filename == null ? "" : filename.replaceAll("^.*[/\\\\]", ""); // drop any folder path
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String name = base.replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            name = "unnamed resume";
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getIdentitySource() {
        return identitySource;
    }

    /** null = no account has claimed this application row. */
    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
