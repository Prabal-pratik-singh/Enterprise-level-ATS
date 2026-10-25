package com.ats.candidate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ats.domain.Application;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.Candidate;
import com.ats.domain.CandidateRepository;
import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A candidate's own applications — the ones their verified e-mail claimed. The caller's identity
 * always comes from the signed token (never from the URL), and every single-item read checks
 * ownership: asking for someone else's application id answers 403.
 */
@RestController
@RequestMapping("/api/candidate/applications")
public class MyApplicationsController {

    public record MyApplication(UUID applicationId, String jobTitle, String status, Instant appliedAt) {
    }

    private final ApplicationRepository applications;
    private final CandidateRepository candidates;
    private final JobRepository jobs;

    public MyApplicationsController(ApplicationRepository applications, CandidateRepository candidates,
                                    JobRepository jobs) {
        this.applications = applications;
        this.candidates = candidates;
        this.jobs = jobs;
    }

    @GetMapping
    public List<MyApplication> mine(@AuthenticationPrincipal Jwt token) {
        List<Application> mine = applications.findAllOwnedBy(callerId(token));
        Map<UUID, String> titles = jobs.findAllById(mine.stream().map(Application::getJobId).distinct().toList())
                .stream().collect(Collectors.toMap(Job::getId, Job::getTitle));
        return mine.stream().map(app -> view(app, titles::get)).toList();
    }

    @GetMapping("/{applicationId}")
    public MyApplication one(@PathVariable UUID applicationId, @AuthenticationPrincipal Jwt token) {
        Application app = applications.findById(applicationId).orElseThrow(MyApplicationsController::notFound);
        Candidate owner = candidates.findById(app.getCandidateId()).orElseThrow(MyApplicationsController::notFound);
        if (!callerId(token).equals(owner.getUserId())) {   // unclaimed (null) or someone else's
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your application");
        }
        return view(app, jobId -> jobs.findById(jobId).map(Job::getTitle).orElse(null));
    }

    private static MyApplication view(Application app, Function<UUID, String> titleOf) {
        return new MyApplication(app.getId(), titleOf.apply(app.getJobId()), app.getStatus().name(), app.getCreatedAt());
    }

    private static UUID callerId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such application");
    }
}
