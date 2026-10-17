package com.ats.api;

import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.Candidate;
import com.ats.domain.CandidateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bulk upload, one file per call: a placeholder candidate + an application + a presigned
 * upload link. The browser then PUTs the file and calls the existing /complete — from there
 * it's the normal pipeline, and the extractor fills in the real name/email from the resume.
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/intake")
public class IntakeController {

    public record IntakeRequest(String filename) {
    }

    public record IntakeResponse(UUID applicationId, UUID candidateId, String displayName,
                                 ResumeService.UploadUrl upload) {
    }

    private final CandidateRepository candidates;
    private final ApplicationService applications;
    private final ResumeService resumes;

    public IntakeController(CandidateRepository candidates, ApplicationService applications, ResumeService resumes) {
        this.candidates = candidates;
        this.applications = applications;
        this.resumes = resumes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional   // an unknown job (404) rolls the placeholder candidate back too
    public IntakeResponse intake(@PathVariable UUID jobId, @RequestBody IntakeRequest req) {
        if (req == null || req.filename() == null || req.filename().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename is required");
        }
        ResumeService.requireSupportedType(req.filename());   // pdf/docx/png/jpg — fail BEFORE creating rows
        Candidate candidate = candidates.save(Candidate.pendingFromFile(req.filename()));
        Application app = applications.create(jobId, candidate.getId());   // existing service: 404 for unknown job
        return new IntakeResponse(app.getId(), candidate.getId(), candidate.getFullName(),
                resumes.createUploadUrl(app.getId(), req.filename()));
    }
}
