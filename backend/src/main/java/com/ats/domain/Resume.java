package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "resumes")
public class Resume {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private int version;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private String sha256;

    /** PENDING → PARSED / FAILED (written by the parser worker). */
    @Column(name = "parse_status", nullable = false)
    private String parseStatus;

    @Column(name = "parse_method")
    private String parseMethod;

    @Column(name = "ocr_confidence")
    private Double ocrConfidence;

    @Column(name = "parsed_s3_key")
    private String parsedS3Key;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Resume() {
    }

    public Resume(UUID applicationId, int version, String s3Key, String contentType, Long sizeBytes, String sha256) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.version = version;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.parseStatus = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
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

    public String getParseStatus() {
        return parseStatus;
    }

    public String getParseMethod() {
        return parseMethod;
    }

    public Double getOcrConfidence() {
        return ocrConfidence;
    }

    public String getParsedS3Key() {
        return parsedS3Key;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
