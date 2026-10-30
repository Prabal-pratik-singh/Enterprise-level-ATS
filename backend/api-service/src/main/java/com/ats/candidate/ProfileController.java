package com.ats.candidate;

import java.util.List;
import java.util.UUID;

import com.ats.api.ResumeService;
import com.ats.domain.ProfileResumeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The candidate's own profile (C2): upload a resume to it, see its versions and the profile read from
 * it, fix what the extractor got wrong, and follow it live. Everything is scoped to the account in the
 * signed token — there is no id in any URL here.
 */
@RestController
@RequestMapping("/api/candidate/profile")
public class ProfileController {

    public record UploadUrlRequest(String filename) {
    }

    public record CompleteRequest(String key) {
    }

    private final ProfileResumeService profileResumes;
    private final ProfileResumeRepository resumes;
    private final ProfileViews views;
    private final ProfileOverridesService overrides;
    private final ProfileSseHub hub;

    public ProfileController(ProfileResumeService profileResumes, ProfileResumeRepository resumes,
                             ProfileViews views, ProfileOverridesService overrides, ProfileSseHub hub) {
        this.profileResumes = profileResumes;
        this.resumes = resumes;
        this.views = views;
        this.overrides = overrides;
        this.hub = hub;
    }

    @PostMapping("/resume/upload-url")
    public ResumeService.UploadUrl uploadUrl(@RequestBody UploadUrlRequest req, @AuthenticationPrincipal Jwt token) {
        return profileResumes.createUploadUrl(callerId(token), req.filename());
    }

    @PostMapping("/resume/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileViews.ResumeVersion complete(@RequestBody CompleteRequest req, @AuthenticationPrincipal Jwt token) {
        return ProfileViews.ResumeVersion.of(profileResumes.complete(callerId(token), req.key()));
    }

    @GetMapping("/resumes")
    public List<ProfileViews.ResumeVersion> versions(@AuthenticationPrincipal Jwt token) {
        return resumes.findByUserIdOrderByVersionDesc(callerId(token)).stream()
                .map(ProfileViews.ResumeVersion::of).toList();
    }

    @GetMapping
    public ProfileViews.ProfileView profile(@AuthenticationPrincipal Jwt token) {
        return views.viewOf(callerId(token));
    }

    /** Her fixes, replaced as a whole; the answer is the profile with them applied. */
    @PutMapping("/overrides")
    public ProfileViews.ProfileView saveOverrides(@RequestBody ProfileOverridesService.Overrides req,
                                                  @AuthenticationPrincipal Jwt token) {
        UUID me = callerId(token);
        overrides.save(me, req);
        return views.viewOf(me);
    }

    /** Live status: one "profile" event with the whole view whenever the pipeline moves. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal Jwt token) {
        return hub.register(callerId(token));
    }

    private static UUID callerId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
