package com.ats.dedup;

import java.util.List;

import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dedup Service front door: resume.extracted in (its own group "dedup-service"),
 * application.deduped out. Signature → buckets → look-alikes → the approved rules.
 */
@Component
public class ResumeExtractedConsumer {

    static final String CONSUMER_NAME = "dedup";

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final DedupRepository repository;
    private final DedupRecorder recorder;
    private final MinHash minHash = new MinHash(); // fixed seed: identical in every replica

    public ResumeExtractedConsumer(ObjectMapper mapper, ProcessedEvents processed,
                                   DedupRepository repository, DedupRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.repository = repository;
        this.recorder = recorder;
    }

    @KafkaListener(topics = Topics.RESUME_EXTRACTED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        DedupRepository.Subject subject = repository.load(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("nothing to dedup for " + event.applicationId()));
        long[] signature = minHash.signature(MinHash.shingles(experienceText(mapper.readTree(subject.profileJson()))));
        long[] buckets = Lsh.bucketKeys(signature);
        DuplicateFinder.Applicant me = subject.applicant().withSignature(signature);

        List<DuplicateFinder.Applicant> pool = repository.lookalikes(me.applicationId(), me.sha256(),
                DuplicateFinder.normalizeEmail(me.email()), DuplicateFinder.normalizePhone(me.phone()), buckets);
        DuplicateFinder.Result result = DuplicateFinder.find(me, pool);

        recorder.record(event, subject, me, signature, buckets, result);
        log.info("deduped application {}: cluster={} findings={}", event.applicationId(), result.clusterId(),
                result.findings().stream().map(f -> f.kind() + "~" + f.other().name()).toList());
    }

    /** Work history = what a copier copies: every role's title, company and description. */
    static String experienceText(JsonNode profile) {
        StringBuilder text = new StringBuilder();
        for (JsonNode job : profile.path("experience")) {
            text.append(job.path("title").asText("")).append(' ')
                .append(job.path("company").asText("")).append(' ')
                .append(job.path("description").asText("")).append(". ");
        }
        return text.toString();
    }
}
