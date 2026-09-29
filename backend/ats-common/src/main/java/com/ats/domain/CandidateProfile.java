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
 * The canonical extracted profile — one per application. The `embedding`
 * column of this table is intentionally unmapped here: pgvector isn't a JPA
 * type; the Phase 7 embedder writes it via plain SQL.
 */
@Entity
@Table(name = "candidate_profiles")
public class CandidateProfile {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    /** The full profile JSON (contact, experience, skills, ..., derived). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String profile;

    /** 0..1 — how completely filled this resume is (feeds the ResumeQuality score). */
    private Double completeness;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CandidateProfile() {
    }

    public CandidateProfile(UUID applicationId, UUID candidateId, String profileJson, Double completeness) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.candidateId = candidateId;
        this.profile = profileJson;
        this.completeness = completeness;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Re-extraction (e.g. resume v2) replaces the profile in place — an upsert. */
    public void replaceProfile(String profileJson, Double completeness) {
        this.profile = profileJson;
        this.completeness = completeness;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public String getProfile() {
        return profile;
    }

    public Double getCompleteness() {
        return completeness;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
