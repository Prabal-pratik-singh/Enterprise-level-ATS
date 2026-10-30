package com.ats.embedder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.embedding.EmbeddingClient;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.ats.profile.EffectiveProfile;
import com.ats.profile.ExperienceMath;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The embedder embeds what the page shows (her fixes included) and ignores failure notices. */
class ProfileChangedConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final MasterProfileRepository profiles = mock(MasterProfileRepository.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final ProfileEmbeddingRecorder recorder = mock(ProfileEmbeddingRecorder.class);
    private final ProfileChangedConsumer consumer = new ProfileChangedConsumer(mapper, processed, profiles,
            new EffectiveProfile(new ExperienceMath()), embeddings, recorder);

    private final UUID sneha = UUID.randomUUID();

    private String changed(String reason) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("event_type", Topics.PROFILE_CHANGED);
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("subject_type", "PROFILE");
        envelope.put("subject_id", sneha.toString());
        envelope.put("data", Map.of("reason", reason));
        envelope.put("schema_version", 1);
        return mapper.writeValueAsString(envelope);
    }

    @Test
    void aFailureNoticeIsNotEmbedded() throws Exception {
        consumer.handle(changed("parse_failed"));

        verifyNoInteractions(embeddings, recorder);
    }

    @Test
    void theEffectiveProfileIsEmbeddedSoHerFixIsInTheVector() throws Exception {
        MasterProfile profile = new MasterProfile(sneha);
        profile.replaceExtracted(UUID.randomUUID(), "{\"skills\": [\"java\"], \"experience\": []}", 0.5);
        profile.replaceOverrides("{\"skills_added\": [\"kafka\"]}", 0.5);
        when(profiles.findById(sneha)).thenReturn(Optional.of(profile));
        when(embeddings.embedDocument(anyString())).thenReturn(new float[768]);

        consumer.handle(changed("edited"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(embeddings).embedDocument(text.capture());
        assertThat(text.getValue()).contains("java").contains("kafka");
        verify(recorder).record(any(), eq(sneha), eq("edited"), any(float[].class), anyInt());
    }
}
