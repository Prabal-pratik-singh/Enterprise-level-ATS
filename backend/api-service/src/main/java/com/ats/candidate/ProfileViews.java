package com.ats.candidate;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.profile.EffectiveProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * What the profile page shows for one account — built in one place for the GET, the PUT of a fix and
 * the live SSE pushes, so all three always agree. `profile` is the EFFECTIVE profile: what was read
 * from the newest resume, with the candidate's own fixes applied.
 */
@Service
public class ProfileViews {

    public record ResumeVersion(UUID id, int version, String status, String error, Instant uploadedAt) {
        static ResumeVersion of(ProfileResume r) {
            return new ResumeVersion(r.getId(), r.getVersion(), r.getStatus(), r.getError(), r.getCreatedAt());
        }
    }

    /** latestResume: the newest upload, whatever its state; profile: null until a resume was read. */
    public record ProfileView(ResumeVersion latestResume, JsonNode profile, JsonNode overrides,
                              Double completeness, boolean embedded, Instant updatedAt) {
    }

    private final ProfileResumeRepository resumes;
    private final MasterProfileRepository profiles;
    private final EffectiveProfile effectiveProfile;
    private final ObjectMapper mapper;

    public ProfileViews(ProfileResumeRepository resumes, MasterProfileRepository profiles,
                        EffectiveProfile effectiveProfile, ObjectMapper mapper) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.effectiveProfile = effectiveProfile;
        this.mapper = mapper;
    }

    public ProfileView viewOf(UUID userId) {
        ResumeVersion latest = resumes.findByUserIdOrderByVersionDesc(userId).stream()
                .findFirst().map(ResumeVersion::of).orElse(null);
        MasterProfile profile = profiles.findById(userId).orElse(null);
        if (profile == null) {   // nothing has been read yet — e.g. right after the first upload
            return new ProfileView(latest, null, mapper.createObjectNode(), null, false, null);
        }
        JsonNode overrides = read(profile.getProfileOverrides());
        JsonNode effective = profile.getProfile() == null ? null
                : effectiveProfile.apply(read(profile.getProfile()), overrides, YearMonth.now());
        return new ProfileView(latest, effective, overrides, profile.getCompleteness(),
                profiles.hasEmbedding(userId).orElse(false), profile.getUpdatedAt());
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored profile JSON is unreadable", e);
        }
    }
}
