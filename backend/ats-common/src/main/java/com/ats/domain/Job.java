package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    private String description;

    /** Structured requirements: must_have[], nice_to_have[], min_exp_months, fresher_friendly, weights. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String requirements;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** A recruiter-created job. requirements = the JSON the matcher reads (must_have, nice_to_have, ...). */
    public Job(String title, String description, String requirementsJson) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.requirements = requirementsJson;
        this.createdAt = Instant.now();
    }

    protected Job() {
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getRequirements() {
        return requirements;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
