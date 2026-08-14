package com.ats.api;

import java.time.Instant;
import java.util.UUID;

import com.ats.domain.Application;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApplicationController {

    private final ApplicationService applications;

    public ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    public record CreateApplicationRequest(UUID candidateId) {
    }

    public record ApplicationResponse(UUID id, UUID jobId, UUID candidateId, String status, Instant createdAt) {
    }

    @PostMapping("/jobs/{jobId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse create(@PathVariable UUID jobId, @RequestBody CreateApplicationRequest req) {
        if (req.candidateId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidateId is required");
        }
        Application app = applications.create(jobId, req.candidateId());
        return new ApplicationResponse(app.getId(), app.getJobId(), app.getCandidateId(),
                app.getStatus().name(), app.getCreatedAt());
    }
}
