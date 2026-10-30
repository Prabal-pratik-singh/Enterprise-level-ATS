package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A candidate's master profile (V12): one per account. `profile` is what the extractor read from the
 * newest resume and is replaced on every new upload; `profileOverrides` holds the candidate's own fixes
 * and is never touched by a re-parse. The embedding vector is written with plain SQL by the embedder
 * (pgvector has no JPA type here), so it is not mapped.
 */
@Entity
@Table(name = "candidate_master_profiles")
public class MasterProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "current_resume_id")
    private UUID currentResumeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String profile;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_overrides", columnDefinition = "jsonb", nullable = false)
    private String profileOverrides;

    private Double completeness;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MasterProfile() {
    }

    /** A profile that exists before anything was extracted — just "this account has a resume on the way". */
    public MasterProfile(UUID userId) {
        this.userId = userId;
        this.profileOverrides = "{}";
        this.updatedAt = Instant.now();
    }

    /**
     * A newly extracted resume becomes the profile. Only the extracted part is replaced — the
     * candidate's own fixes (profileOverrides) stay exactly as they were.
     */
    public void replaceExtracted(UUID resumeId, String profileJson, double completeness) {
        this.currentResumeId = resumeId;
        this.profile = profileJson;
        this.completeness = completeness;
        this.updatedAt = Instant.now();
    }

    /** The candidate's own fixes (replaced as a whole), and the completeness they lead to. */
    public void replaceOverrides(String overridesJson, double effectiveCompleteness) {
        this.profileOverrides = overridesJson;
        this.completeness = effectiveCompleteness;
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCurrentResumeId() {
        return currentResumeId;
    }

    public String getProfile() {
        return profile;
    }

    public String getProfileOverrides() {
        return profileOverrides;
    }

    public Double getCompleteness() {
        return completeness;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
