package com.ats.api;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.CandidateRepository;
import com.ats.domain.JobRepository;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final JobRepository jobs;
    private final CandidateRepository candidates;
    private final OutboxPublisher outbox;

    public ApplicationService(ApplicationRepository applications, ApplicationEventRepository events,
                              JobRepository jobs, CandidateRepository candidates, OutboxPublisher outbox) {
        this.applications = applications;
        this.events = events;
        this.jobs = jobs;
        this.candidates = candidates;
        this.outbox = outbox;
    }

    @Transactional
    public Application create(UUID jobId, UUID candidateId) {
        jobs.findById(jobId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
        candidates.findById(candidateId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "candidate not found: " + candidateId));

        Application app = new Application(jobId, candidateId);
        try {
            applications.saveAndFlush(app);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "candidate has already applied to this job");
        }
        events.save(new ApplicationEvent(app.getId(), "APPLIED", "{}"));
        outbox.append(Topics.APPLICATION_CREATED, app.getId(), jobId,
                Map.of("candidate_id", candidateId.toString()));
        return app;
    }
}
