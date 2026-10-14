package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One scoring row per application, shared by TWO writers:
 *  - matcher-service writes match_score / components / evidence
 *  - fraud-service   writes fraud_score / flags / fraud_penalty
 * final_score is computed by Postgres (V5 generated column) — read-only here.
 */
@Entity
@Table(name = "scores")
@DynamicUpdate // UPDATE only the columns this service changed — without it JPA rewrites
               // EVERY column and would silently erase the other service's half of the row
public class Score {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(name = "match_score")
    private Double matchScore;     // 0..100 from the matcher (null until it has run)

    @Column(name = "fraud_penalty", nullable = false)
    private Double fraudPenalty;   // points subtracted by the Trust Service

    // Computed by Postgres as match_score - fraud_penalty; JPA must never send it
    // (Postgres rejects writes to a generated column).
    @Column(name = "final_score", insertable = false, updatable = false)
    private Double finalScore;

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
        this.fraudPenalty = 0.0;   // no penalty until the Trust Service says otherwise
        this.flags = "[]";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Matcher's write: the explainable match result (fraud fields untouched). */
    public void updateMatch(double matchScore, String componentsJson, String evidenceJson) {
        this.matchScore = matchScore;
        this.components = componentsJson;
        this.evidence = evidenceJson;
        this.updatedAt = Instant.now();
    }

    /** Trust Service's write: fraud verdict + points to subtract (match fields untouched). */
    public void updateTrust(double fraudScore, double penalty, String flagsJson) {
        this.fraudScore = fraudScore;
        this.fraudPenalty = penalty;   // Postgres re-derives final_score = match_score - penalty
        this.flags = flagsJson;
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

    public Double getMatchScore() {
        return matchScore;
    }

    public Double getFraudPenalty() {
        return fraudPenalty;
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
