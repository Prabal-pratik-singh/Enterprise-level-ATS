package com.ats.candidate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import com.ats.profile.EffectiveProfile;
import com.ats.profile.ExperienceMath;
import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Her fixes: stored in one clean spelling, checked, and announced so the embedder refreshes. */
class ProfileOverridesServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MasterProfileRepository profiles = mock(MasterProfileRepository.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final ProfileOverridesService service = new ProfileOverridesService(profiles,
            new SkillTaxonomy(mapper), new EffectiveProfile(new ExperienceMath()), outbox, mapper);

    private final UUID sneha = UUID.randomUUID();
    private final MasterProfile profile = new MasterProfile(sneha);

    @BeforeEach
    void herProfileWasRead() {
        profile.replaceExtracted(UUID.randomUUID(), """
                {"skills": ["spring-boot", "html"],
                 "experience": [{"company": "Hyperloop Retail", "title": "Software Engineer",
                                 "start_date": "2021-07", "end_date": "2023-09", "current": false}]}""", 0.6);
        when(profiles.findById(sneha)).thenReturn(Optional.of(profile));
    }

    private ProfileOverridesService.Overrides fixes(List<String> added, List<String> removed,
                                                    Map<String, ProfileOverridesService.DateFix> experience) {
        return new ProfileOverridesService.Overrides(added, removed, experience);
    }

    @Test
    void herFixesAreStoredInOneSpellingAndTheEmbedderIsTold() throws Exception {
        service.save(sneha, fixes(List.of(" Kafka ", "Spring Boot"), List.of("HTML"),
                Map.of("Hyperloop Retail|Software Engineer", new ProfileOverridesService.DateFix(null, "2024-06"))));

        JsonNode stored = mapper.readTree(profile.getProfileOverrides());
        assertThat(stored.path("skills_added").toString()).isEqualTo("[\"kafka\",\"spring-boot\"]");
        assertThat(stored.path("skills_removed").toString()).isEqualTo("[\"html\"]");
        assertThat(stored.at("/experience/hyperloop retail|software engineer/end_date").asText()).isEqualTo("2024-06");
        verify(profiles).save(profile);
        verify(outbox).appendForSubject(eq(Topics.PROFILE_CHANGED), eq(EventEnvelope.PROFILE), eq(sneha),
                eq(Map.of("reason", "edited")));
    }

    @Test
    void aSkillInBothListsIsKept() throws Exception {
        service.save(sneha, fixes(List.of("kafka"), List.of("kafka"), null));

        JsonNode stored = mapper.readTree(profile.getProfileOverrides());
        assertThat(stored.path("skills_added").toString()).isEqualTo("[\"kafka\"]");
        assertThat(stored.path("skills_removed").size()).isZero();
    }

    @Test
    void datesMustBeRealPastMonthsInTheRightOrder() {
        assertThatThrownBy(() -> service.save(sneha, fixes(null, null, Map.of("Hyperloop Retail|Software Engineer",
                new ProfileOverridesService.DateFix(null, "June 2024"))))).hasMessageContaining("must look like 2024-06");
        assertThatThrownBy(() -> service.save(sneha, fixes(null, null, Map.of("Hyperloop Retail|Software Engineer",
                new ProfileOverridesService.DateFix(null, "2099-01"))))).hasMessageContaining("cannot be in the future");
        assertThatThrownBy(() -> service.save(sneha, fixes(null, null, Map.of("Hyperloop Retail|Software Engineer",
                new ProfileOverridesService.DateFix("2024-01", "2023-09"))))).hasMessageContaining("must not be after");
        verify(outbox, never()).appendForSubject(anyString(), anyString(), eq(sneha), anyMap());
    }

    @Test
    void aRoleThatIsNotInHerProfileIsRefused() {
        assertThatThrownBy(() -> service.save(sneha, fixes(null, null,
                Map.of("Acme|CEO", new ProfileOverridesService.DateFix("2020-01", null)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no role 'Acme|CEO' in your profile");
    }

    @Test
    void thereIsNothingToEditBeforeAResumeWasRead() {
        UUID newcomer = UUID.randomUUID();
        when(profiles.findById(newcomer)).thenReturn(Optional.of(new MasterProfile(newcomer)));   // no profile yet

        assertThatThrownBy(() -> service.save(newcomer, fixes(List.of("kafka"), null, null)))
                .hasMessageContaining("upload a resume first");
    }
}
