package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One resume a candidate uploaded to their OWN profile (V12) — not to a job. Versions count up per
 * account; the status follows the profile pipeline: UPLOADED → PARSED → EXTRACTED (or a *_FAILED).
 */
@Entity
@Table(name = "profile_resumes")
public class ProfileResume {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int version;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private String sha256;

    @Column(nullable = false)
    private String status;          // UPLOADED | PARSED | EXTRACTED | PARSE_FAILED | EXTRACT_FAILED

    @Column(name = "parsed_s3_key")
    private String parsedS3Key;

    private String error;           // why it failed, in plain words

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfileResume() {
    }

    public ProfileResume(UUID userId, int version, String s3Key, String contentType, Long sizeBytes, String sha256) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.version = version;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.status = "UPLOADED";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getVersion() {
        return version;
    }

    public String getS3Key() {
        return s3Key;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStatus() {
        return status;
    }

    public String getParsedS3Key() {
        return parsedS3Key;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
