package com.ats.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The dashboard's landing data — which jobs exist and how many applied — and creating new ones. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobSummary(UUID id, String title, String description,
                             boolean fresherFriendly, long applicants) {
    }

    public record CreateJobRequest(String title, String description, List<String> mustHave,
                                   List<String> niceToHave, Integer minExpMonths, Boolean fresherFriendly) {
    }

    public record CreatedJob(JobSummary job, List<String> unknownSkills) {
    }

    private final JobRepository jobs;
    private final CandidateQueryService candidates;
    private final SkillTaxonomy taxonomy;
    private final ObjectMapper mapper;

    public JobController(JobRepository jobs, CandidateQueryService candidates, SkillTaxonomy taxonomy,
                         ObjectMapper mapper) {
        this.jobs = jobs;
        this.candidates = candidates;
        this.taxonomy = taxonomy;
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

    /**
     * A recruiter creates a job. Skills are normalised exactly like the extractor normalises
     * resumes ("Spring Boot" → "spring-boot"), otherwise the matcher could never match them.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatedJob create(@RequestBody CreateJobRequest req) {
        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty() || title.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required (max 120 characters)");
        }
        List<String> unknown = new ArrayList<>();
        ObjectNode requirements = requirementsFor(req, unknown);
        boolean fresher = requirements.path("fresher_friendly").asBoolean();

        Job job = jobs.save(new Job(title, blankToNull(req.description()), requirements.toString()));
        return new CreatedJob(new JobSummary(job.getId(), job.getTitle(), job.getDescription(), fresher, 0),
                unknown.stream().distinct().toList());
    }

    /** The requirements JSON exactly as the matcher's JobRequirements.parse reads it. */
    ObjectNode requirementsFor(CreateJobRequest req, List<String> unknown) {
        int minExp = req.minExpMonths() == null ? 0 : req.minExpMonths();
        if (minExp < 0 || minExp > 600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minExpMonths must be between 0 and 600");
        }
        List<String> must = normalize(req.mustHave(), unknown);
        if (must.isEmpty()) {
            // with zero must-haves the matcher gives EVERYONE full marks for that part — rankings lose meaning
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "add at least one must-have skill");
        }
        List<String> nice = normalize(req.niceToHave(), unknown).stream()
                .filter(skill -> !must.contains(skill))   // typed in both lists → it's a must-have
                .toList();

        ObjectNode requirements = mapper.createObjectNode();
        requirements.put("weights", "default");
        must.forEach(requirements.putArray("must_have")::add);
        nice.forEach(requirements.putArray("nice_to_have")::add);
        requirements.put("min_exp_months", minExp);
        requirements.put("fresher_friendly", Boolean.TRUE.equals(req.fresherFriendly()));
        return requirements;
    }

    /** Canonical names, de-duplicated, order kept; unknown spellings are kept (lower-cased) and reported. */
    private List<String> normalize(List<String> raw, List<String> unknown) {
        Set<String> out = new LinkedHashSet<>();
        for (String skill : raw == null ? List.<String>of() : raw) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            Optional<String> canonical = taxonomy.canonical(skill);
            if (canonical.isEmpty()) {
                unknown.add(skill.trim().toLowerCase(Locale.ROOT));
            }
            out.add(canonical.orElse(skill.trim().toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(out);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean fresherFlag(Job job) {
        try {
            return mapper.readTree(job.getRequirements()).path("fresher_friendly").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
