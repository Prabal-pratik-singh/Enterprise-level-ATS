package com.ats.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit trail: one row per status transition or notable event. */
@Entity
@Table(name = "application_events")
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApplicationEvent() {
    }

    public ApplicationEvent(UUID applicationId, String eventType, String detailsJson) {
        this.applicationId = applicationId;
        this.eventType = eventType;
        this.details = detailsJson;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
