package com.ats.api;

import java.time.Instant;
import java.util.UUID;

import com.ats.domain.Candidate;
import com.ats.domain.CandidateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateRepository candidates;

    public CandidateController(CandidateRepository candidates) {
        this.candidates = candidates;
    }

    public record CreateCandidateRequest(String name, String email, String phone) {
    }

    public record CandidateResponse(UUID id, String name, String email, String phone, Instant createdAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateResponse create(@RequestBody CreateCandidateRequest req) {
        if (req.name() == null || req.name().isBlank() || req.email() == null || req.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and email are required");
        }
        Candidate saved = candidates.save(new Candidate(req.name().trim(), req.email().trim(), req.phone()));
        return new CandidateResponse(saved.getId(), saved.getFullName(), saved.getEmail(),
                saved.getPhone(), saved.getCreatedAt());
    }
}
