package com.ats.api.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** What the LLM needs to know about the job — read from the same requirements the matcher uses. */
public record JobBrief(String title, List<String> mustHave, List<String> niceToHave, int minExpMonths) {

    @Component
    public static class Loader {

        private final JobRepository jobs;
        private final ObjectMapper mapper;

        public Loader(JobRepository jobs, ObjectMapper mapper) {
            this.jobs = jobs;
            this.mapper = mapper;
        }

        public JobBrief forJob(UUID jobId) {
            Job job = jobs.findById(jobId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
            try {
                JsonNode r = mapper.readTree(job.getRequirements());
                return new JobBrief(job.getTitle(), strings(r.path("must_have")), strings(r.path("nice_to_have")),
                        r.path("min_exp_months").asInt(0));
            } catch (Exception e) {
                throw new IllegalStateException("unreadable requirements for job " + jobId, e);
            }
        }

        private static List<String> strings(JsonNode array) {
            List<String> out = new ArrayList<>();
            array.forEach(s -> out.add(s.asText()));
            return out;
        }
    }
}
