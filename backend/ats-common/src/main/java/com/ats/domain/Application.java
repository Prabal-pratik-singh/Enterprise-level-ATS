package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "applications")
public class Application {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // V8: created by the load test (seed.sh --bulk) → the extractor uses rules, never the LLM
    @Column(name = "load_test", nullable = false)
    private boolean loadTest;

    protected Application() {
    }

    public Application(UUID jobId, UUID candidateId) {
        this(jobId, candidateId, false);   // every real application
    }

    public Application(UUID jobId, UUID candidateId, boolean loadTest) {
        this.id = UUID.randomUUID();
        this.jobId = jobId;
        this.candidateId = candidateId;
        this.status = ApplicationStatus.APPLIED;
        this.loadTest = loadTest;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void transitionTo(ApplicationStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public boolean isLoadTest() {
        return loadTest;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
