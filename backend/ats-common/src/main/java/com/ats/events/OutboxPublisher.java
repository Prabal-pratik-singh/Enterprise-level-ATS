package com.ats.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ats.domain.OutboxRepository;
import com.ats.domain.OutboxRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the outbox table. MUST be called inside the
 * caller's transaction so the event commits or rolls back atomically with the
 * business rows. Envelope: {event_id, event_type, occurred_at, application_id,
 * job_id, subject_type, subject_id, data{}, schema_version}. The Kafka key is the
 * subject id, so every event about one application (or one profile) stays in order.
 */
@Component
public class OutboxPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OutboxPublisher(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** An event about an application (every Part 1 event). */
    public UUID append(String topic, UUID applicationId, UUID jobId, Map<String, Object> data) {
        return write(topic, EventEnvelope.APPLICATION, applicationId, applicationId, jobId, data);
    }

    /** Part 2: an event about something other than an application, e.g. a candidate's own profile. */
    public UUID appendForSubject(String topic, String subjectType, UUID subjectId, Map<String, Object> data) {
        return write(topic, subjectType, subjectId, null, null, data);
    }

    private UUID write(String topic, String subjectType, UUID subjectId, UUID applicationId, UUID jobId,
                       Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", topic);
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("application_id", applicationId == null ? null : applicationId.toString());
        envelope.put("job_id", jobId == null ? null : jobId.toString());
        envelope.put("subject_type", subjectType);
        envelope.put("subject_id", subjectId.toString());
        envelope.put("data", data);
        envelope.put("schema_version", 1);
        try {
            String json = mapper.writeValueAsString(envelope);
            outbox.save(new OutboxRow(eventId, topic, subjectId.toString(), topic, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox event for " + topic, e);
        }
        return eventId;
    }
}
