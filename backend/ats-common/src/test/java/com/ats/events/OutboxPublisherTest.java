package com.ats.events;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.OutboxRepository;
import com.ats.domain.OutboxRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The envelope's subject (Part 2): what an event is about, and the Kafka key that keeps it in order. */
class OutboxPublisherTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();   // + java.time for Instant
    private final OutboxPublisher outbox = new OutboxPublisher(repository, mapper);

    private OutboxRow saved() {
        ArgumentCaptor<OutboxRow> row = ArgumentCaptor.forClass(OutboxRow.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void anApplicationEventStaysKeyedByItsApplication() throws Exception {
        UUID application = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        outbox.append(Topics.RESUME_UPLOADED, application, job, Map.of("s3_key", "resumes/x/v1/resume.pdf"));

        OutboxRow row = saved();
        EventEnvelope event = mapper.readValue(row.getPayload(), EventEnvelope.class);
        assertThat(row.getEventKey()).isEqualTo(application.toString());
        assertThat(event.subject()).isEqualTo(EventEnvelope.APPLICATION);
        assertThat(event.subjectId()).isEqualTo(application);
        assertThat(event.applicationId()).isEqualTo(application);
        assertThat(event.jobId()).isEqualTo(job);
    }

    @Test
    void aProfileEventIsKeyedByTheAccountAndHasNoApplication() throws Exception {
        UUID account = UUID.randomUUID();
        outbox.appendForSubject(Topics.PROFILE_RESUME_UPLOADED, EventEnvelope.PROFILE, account, Map.of("version", 1));

        OutboxRow row = saved();
        EventEnvelope event = mapper.readValue(row.getPayload(), EventEnvelope.class);
        assertThat(row.getEventKey()).isEqualTo(account.toString());
        assertThat(event.subject()).isEqualTo(EventEnvelope.PROFILE);
        assertThat(event.subjectId()).isEqualTo(account);
        assertThat(event.applicationId()).isNull();
        assertThat(event.jobId()).isNull();
        assertThat(event.data().path("version").asInt()).isEqualTo(1);
    }

    @Test
    void anEventWrittenBeforePart2IsAboutAnApplication() throws Exception {
        // what is still in Kafka from Part 1: no subject fields at all
        EventEnvelope old = mapper.readValue("""
                {"event_id":"7b0f2c1e-0d3a-4b1e-9a51-3f2a5c6d7e80","event_type":"resume.uploaded",
                 "occurred_at":"2026-10-01T10:00:00Z","application_id":"0b6f1a2c-3d4e-4f50-8a61-7b8c9d0e1f23",
                 "job_id":"00000000-0000-0000-0000-000000000001","data":{"s3_key":"resumes/x"},"schema_version":1}""",
                EventEnvelope.class);

        assertThat(old.subject()).isEqualTo(EventEnvelope.APPLICATION);
        assertThat(old.subjectId()).isNull();
        assertThat(old.dataText("s3_key")).isEqualTo("resumes/x");
    }

    @Test
    void theFourProfileTopicsAreCreatedWithTheRest() {
        assertThat(Topics.ALL).contains("profile.resume.uploaded", "profile.resume.parsed",
                "profile.changed", "profile.embedded");
    }
}
