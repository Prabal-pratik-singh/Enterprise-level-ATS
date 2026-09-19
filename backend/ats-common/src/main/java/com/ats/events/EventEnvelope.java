package com.ats.events;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The wire format every Kafka topic carries. OutboxPublisher writes this JSON;
 * every consumer reads it back into this record. Payloads reference S3 keys
 * and database ids — never raw file bytes (files stay in MinIO).
 *
 * Example message:
 * {"event_id":"...","event_type":"resume.uploaded","occurred_at":"...",
 *  "application_id":"...","job_id":"...","data":{"s3_key":"..."},"schema_version":1}
 */
// If a future producer adds a new JSON field, old consumers just ignore it
// instead of crashing — that's what "ignoreUnknown" buys us.
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        // @JsonProperty maps the snake_case JSON names to our Java names
        @JsonProperty("event_id") UUID eventId,             // unique id of THIS event — used for deduplication
        @JsonProperty("event_type") String eventType,       // same as the topic name, e.g. "resume.uploaded"
        @JsonProperty("occurred_at") Instant occurredAt,    // when the event was created (not when it was delivered)
        @JsonProperty("application_id") UUID applicationId, // the aggregate this event belongs to (also the Kafka key)
        @JsonProperty("job_id") UUID jobId,                 // which job posting this application targets
        @JsonProperty("data") JsonNode data,                // event-specific payload, e.g. {"resume_id":..., "s3_key":...}
        @JsonProperty("schema_version") int schemaVersion) {// lets us evolve the format later without breaking readers

    /**
     * Convenience for consumers: read a required text field out of `data`,
     * failing loudly (goes to retry → DLQ) if a producer forgot to set it.
     */
    public String dataText(String field) {
        JsonNode node = data == null ? null : data.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalStateException("event " + eventId + " is missing data field '" + field + "'");
        }
        return node.asText();
    }
}
