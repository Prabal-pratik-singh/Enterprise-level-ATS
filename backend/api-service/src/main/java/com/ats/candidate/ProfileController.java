package com.ats.candidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ats.api.ResumeService;
import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The candidate's own profile (C2): upload a resume to it, see its versions and the profile read from
 * it. Everything is scoped to the account in the signed token — there is no id in any URL here.
 */
@RestController
@RequestMapping("/api/candidate/profile")
public class ProfileController {

    public record UploadUrlRequest(String filename) {
    }

    public record CompleteRequest(String key) {
    }

    public record ResumeVersion(UUID id, int version, String status, String error, Instant uploadedAt) {
        static ResumeVersion of(ProfileResume r) {
            return new ResumeVersion(r.getId(), r.getVersion(), r.getStatus(), r.getError(), r.getCreatedAt());
        }
    }

    /** latestResume: the newest upload, whatever its state; profile: null until a resume was read. */
    public record ProfileView(ResumeVersion latestResume, JsonNode profile, JsonNode overrides,
                              Double completeness, boolean embedded, Instant updatedAt) {
    }

    private final ProfileResumeService profileResumes;
    private final ProfileResumeRepository resumes;
    private final MasterProfileRepository profiles;
    private final ObjectMapper mapper;

    public ProfileController(ProfileResumeService profileResumes, ProfileResumeRepository resumes,
                             MasterProfileRepository profiles, ObjectMapper mapper) {
        this.profileResumes = profileResumes;
        this.resumes = resumes;
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @PostMapping("/resume/upload-url")
    public ResumeService.UploadUrl uploadUrl(@RequestBody UploadUrlRequest req, @AuthenticationPrincipal Jwt token) {
        return profileResumes.createUploadUrl(callerId(token), req.filename());
    }

    @PostMapping("/resume/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public ResumeVersion complete(@RequestBody CompleteRequest req, @AuthenticationPrincipal Jwt token) {
        return ResumeVersion.of(profileResumes.complete(callerId(token), req.key()));
    }

    @GetMapping("/resumes")
    public List<ResumeVersion> versions(@AuthenticationPrincipal Jwt token) {
        return resumes.findByUserIdOrderByVersionDesc(callerId(token)).stream().map(ResumeVersion::of).toList();
    }

    @GetMapping
    public ProfileView profile(@AuthenticationPrincipal Jwt token) throws JsonProcessingException {
        UUID me = callerId(token);
        ResumeVersion latest = resumes.findByUserIdOrderByVersionDesc(me).stream()
                .findFirst().map(ResumeVersion::of).orElse(null);
        MasterProfile profile = profiles.findById(me).orElse(null);
        if (profile == null) {   // nothing has been read yet — e.g. right after the first upload
            return new ProfileView(latest, null, mapper.createObjectNode(), null, false, null);
        }
        return new ProfileView(latest,
                profile.getProfile() == null ? null : mapper.readTree(profile.getProfile()),
                mapper.readTree(profile.getProfileOverrides()),
                profile.getCompleteness(),
                profiles.hasEmbedding(me).orElse(false),
                profile.getUpdatedAt());
    }

    private static UUID callerId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
