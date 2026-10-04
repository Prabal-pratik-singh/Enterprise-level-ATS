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
 * One scoring row per application. The matcher writes final_score/components/
 * evidence; the Phase 6 fraud service writes fraud_score/flags into the SAME
 * row (the spec's design) — hence separate mutators per writer.
 */
@Entity
@Table(name = "scores")
public class Score {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(name = "final_score")
    private Double finalScore;     // 0..100, capped + weighted

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String components;     // {"skill_match":0.82, ...} each 0..1

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String evidence;       // [{"type":"skill","text":"...","positive":true}, ...]

    @Column(name = "fraud_score", nullable = false)
    private Double fraudScore;     // 0..1 — Phase 6 fraud service owns this

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String flags;          // fraud flags — Phase 6 owns this

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Score() {
    }

    public Score(UUID applicationId) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.components = "{}";
        this.evidence = "[]";
        this.fraudScore = 0.0;   // innocent until Phase 6 says otherwise
        this.flags = "[]";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Matcher's write: the explainable match result (fraud fields untouched). */
    public void updateMatch(double finalScore, String componentsJson, String evidenceJson) {
        this.finalScore = finalScore;
        this.components = componentsJson;
        this.evidence = evidenceJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public Double getFinalScore() {
        return finalScore;
    }

    public String getComponents() {
        return components;
    }

    public String getEvidence() {
        return evidence;
    }

    public Double getFraudScore() {
        return fraudScore;
    }

    public String getFlags() {
        return flags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
