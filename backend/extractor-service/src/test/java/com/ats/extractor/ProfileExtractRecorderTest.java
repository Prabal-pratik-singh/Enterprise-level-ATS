package com.ats.extractor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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

/** The master profile after an extraction: created, replaced per version, overrides kept, never rolled back. */
class ProfileExtractRecorderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final ProfileResumeRepository resumes = mock(ProfileResumeRepository.class);
    private final MasterProfileRepository profiles = mock(MasterProfileRepository.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final ProfileExtractRecorder recorder = new ProfileExtractRecorder(processed, resumes, profiles, outbox);

    private final UUID sneha = UUID.randomUUID();
    private final ProfileResume v1 = resume(1);
    private final ProfileResume v2 = resume(2);

    private ProfileResume resume(int version) {
        ProfileResume r = new ProfileResume(sneha, version, "profiles/" + sneha + "/v" + version + "/resume.pdf",
                "application/pdf", 2942L, "sha" + version);
        when(resumes.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private EventEnvelope parsedEvent(ProfileResume resume) {
        return new EventEnvelope(UUID.randomUUID(), Topics.PROFILE_RESUME_PARSED, Instant.now(), null, null,
                EventEnvelope.PROFILE, sneha,
                mapper.createObjectNode().put("profile_resume_id", resume.getId().toString()), 1);
    }

    private ProfileExtractor.ExtractedProfile extracted(String skill) {
        ObjectNode profile = mapper.createObjectNode();
        profile.putArray("skills").add(skill);
        return new ProfileExtractor.ExtractedProfile(profile, 0.85, 26, 1, "llm");
    }

    @BeforeEach
    void ledgerSaysNew() {
        when(processed.markProcessed(any(), anyString())).thenReturn(true);
    }

    @Test
    void theFirstExtractionCreatesHerProfileAndTellsTheEmbedder() {
        when(profiles.findById(sneha)).thenReturn(Optional.empty());

        recorder.recordSuccess(parsedEvent(v1), extracted("java"));

        ArgumentCaptor<MasterProfile> saved = ArgumentCaptor.forClass(MasterProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getProfile()).contains("java");
        assertThat(saved.getValue().getCompleteness()).isEqualTo(0.85);
        assertThat(saved.getValue().getCurrentResumeId()).isEqualTo(v1.getId());
        assertThat(v1.getStatus()).isEqualTo("EXTRACTED");
        verify(outbox).appendForSubject(eq(Topics.PROFILE_CHANGED), eq(EventEnvelope.PROFILE), eq(sneha), anyMap());
    }

    @Test
    void aNewVersionReplacesWhatWasReadButNeverHerOwnFixes() {
        MasterProfile existing = new MasterProfile(sneha);
        existing.replaceExtracted(v1.getId(), "{\"skills\":[\"java\"]}", 0.7);
        ReflectionTestUtils.setField(existing, "profileOverrides", "{\"skills_added\":[\"kafka\"]}");   // her fix (2c UI)
        when(profiles.findById(sneha)).thenReturn(Optional.of(existing));

        recorder.recordSuccess(parsedEvent(v2), extracted("spring-boot"));

        assertThat(existing.getProfile()).contains("spring-boot").doesNotContain("\"java\"");
        assertThat(existing.getProfileOverrides()).isEqualTo("{\"skills_added\":[\"kafka\"]}");
        assertThat(existing.getCurrentResumeId()).isEqualTo(v2.getId());
    }

    @Test
    void aLateAnswerForAnOlderVersionNeverReplacesTheNewerProfile() {
        MasterProfile showsV2 = new MasterProfile(sneha);
        showsV2.replaceExtracted(v2.getId(), "{\"skills\":[\"spring-boot\"]}", 0.9);
        when(profiles.findById(sneha)).thenReturn(Optional.of(showsV2));

        recorder.recordSuccess(parsedEvent(v1), extracted("java"));   // v1's slow answer arrives after v2's

        assertThat(showsV2.getProfile()).contains("spring-boot");
        assertThat(showsV2.getCurrentResumeId()).isEqualTo(v2.getId());
        assertThat(v1.getStatus()).isEqualTo("EXTRACTED");   // v1 was still read
        verify(profiles, never()).save(any());
        verify(outbox, never()).appendForSubject(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void aFailedExtractionTellsTheCandidateToTryAgain() {
        recorder.recordFailure(parsedEvent(v1));

        assertThat(v1.getStatus()).isEqualTo("EXTRACT_FAILED");
        assertThat(v1.getError()).startsWith("We couldn't turn your resume into a profile");
        verify(profiles, never()).save(any());
        verify(outbox).appendForSubject(eq(Topics.PROFILE_CHANGED), eq(EventEnvelope.PROFILE), eq(sneha),
                argThat(data -> "extract_failed".equals(data.get("reason"))));   // an open page updates live
    }
}
