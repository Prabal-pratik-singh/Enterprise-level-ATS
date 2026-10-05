package com.ats.api;

import java.util.List;
import java.util.UUID;

import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard's landing data: which jobs exist and how many applied. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobSummary(UUID id, String title, String description,
                             boolean fresherFriendly, long applicants) {
    }

    private final JobRepository jobs;
    private final CandidateQueryService candidates;
    private final ObjectMapper mapper;

    public JobController(JobRepository jobs, CandidateQueryService candidates, ObjectMapper mapper) {
        this.jobs = jobs;
        this.candidates = candidates;
        this.mapper = mapper;
    }

    @GetMapping
    public List<JobSummary> list() {
        return jobs.findAll().stream()
                .map(job -> new JobSummary(
                        job.getId(), job.getTitle(), job.getDescription(),
                        fresherFlag(job), candidates.countForJob(job.getId())))
                .toList();
    }

    private boolean fresherFlag(Job job) {
        try {
            return mapper.readTree(job.getRequirements()).path("fresher_friendly").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
