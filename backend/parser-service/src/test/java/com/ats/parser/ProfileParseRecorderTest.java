package com.ats.parser;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A profile resume's parse: PARSED + the extractor told; a duplicate does nothing; a failure explains itself. */
class ProfileParseRecorderTest {

    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final ProfileResumeRepository resumes = mock(ProfileResumeRepository.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final ProfileParseRecorder recorder = new ProfileParseRecorder(processed, resumes, outbox);

    private final UUID sneha = UUID.randomUUID();
    private final ProfileResume v1 = new ProfileResume(sneha, 1, "profiles/" + sneha + "/v1/resume.pdf",
            "application/pdf", 2942L, "81000eab");
    private final String parsedKey = "profiles/" + sneha + "/v1/parsed.json";
    private final ParsedDocument doc = ParsedDocument.tika("Sneha Singh — Java, Spring Boot, SQL", 1);

    private EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(), Topics.PROFILE_RESUME_UPLOADED, Instant.now(), null, null,
                EventEnvelope.PROFILE, sneha,
                new ObjectMapper().createObjectNode().put("profile_resume_id", v1.getId().toString()), 1);
    }

    @Test
    void aParsedResumeIsMarkedAndHandedToTheExtractor() {
        when(processed.markProcessed(any(), anyString())).thenReturn(true);

        recorder.recordSuccess(event(), v1, doc, parsedKey);

        assertThat(v1.getStatus()).isEqualTo("PARSED");
        assertThat(v1.getParsedS3Key()).isEqualTo(parsedKey);
        verify(resumes).save(v1);
        verify(outbox).appendForSubject(eq(Topics.PROFILE_RESUME_PARSED), eq(EventEnvelope.PROFILE), eq(sneha), anyMap());
    }

    @Test
    void aDuplicateDeliveryChangesNothing() {
        when(processed.markProcessed(any(), anyString())).thenReturn(false);   // another instance won the race

        recorder.recordSuccess(event(), v1, doc, parsedKey);

        assertThat(v1.getStatus()).isEqualTo("UPLOADED");
        verify(resumes, never()).save(any());
        verify(outbox, never()).appendForSubject(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void aFailedParseTellsTheCandidateWhatToDo() {
        when(processed.markProcessed(any(), anyString())).thenReturn(true);
        when(resumes.findById(v1.getId())).thenReturn(Optional.of(v1));

        recorder.recordFailure(event());

        assertThat(v1.getStatus()).isEqualTo("PARSE_FAILED");
        assertThat(v1.getError()).startsWith("We couldn't read this file");
        // the pipeline ends here: nothing for the extractor, only a notice so an open page updates live
        verify(outbox, never()).appendForSubject(eq(Topics.PROFILE_RESUME_PARSED), anyString(), any(), anyMap());
        verify(outbox).appendForSubject(eq(Topics.PROFILE_CHANGED), eq(EventEnvelope.PROFILE), eq(sneha),
                argThat(data -> "parse_failed".equals(data.get("reason"))));
    }
}
