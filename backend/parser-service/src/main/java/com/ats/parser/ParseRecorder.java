package com.ats.parser;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.ApplicationStatus;
import com.ats.domain.Resume;
import com.ats.domain.ResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transaction at the end of a parse (happy or sad path).
 * Everything commits or rolls back together: idempotency mark, resumes row,
 * application status, audit event, outbox row. Crash before commit → nothing
 * was marked → Kafka redelivers → the retry redoes the work safely.
 */
@Service
public class ParseRecorder {

    private final ProcessedEvents processed;
    private final ResumeRepository resumes;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository auditTrail;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public ParseRecorder(ProcessedEvents processed, ResumeRepository resumes,
                         ApplicationRepository applications, ApplicationEventRepository auditTrail,
                         OutboxPublisher outbox, ObjectMapper mapper) {
        this.processed = processed;
        this.resumes = resumes;
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void recordSuccess(EventEnvelope event, Resume resume, ParsedDocument doc, String parsedKey) {
        // Claim the event inside the transaction. If a parallel parser instance
        // won the race, our claim inserts 0 rows and we quietly drop our copy.
        if (!processed.markProcessed(event.eventId(), ResumeUploadedConsumer.CONSUMER_NAME)) {
            return;
        }

        // The resume entity was loaded outside this transaction (detached), so
        // save() is required to persist the changes.
        resume.markParsed(doc.parseMethod(), doc.ocrConfidence(), parsedKey);
        resumes.save(resume);

        // Status machine: APPLIED → PARSED (+ append-only audit row)
        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));
        app.transitionTo(ApplicationStatus.PARSED); // managed entity — saved automatically at commit
        auditTrail.save(new ApplicationEvent(app.getId(), "PARSED",
                json(Map.of("parse_method", doc.parseMethod(),
                        "page_count", doc.pageCount() == null ? 0 : doc.pageCount()))));

        // Tell the world (extractor is next in line). Goes through the outbox —
        // same transaction — so "status says PARSED but no event" cannot happen.
        outbox.append(Topics.RESUME_PARSED, app.getId(), app.getJobId(), Map.of(
                "resume_id", resume.getId().toString(),
                "parsed_s3_key", parsedKey,
                "parse_method", doc.parseMethod(),
                "page_count", doc.pageCount() == null ? 0 : doc.pageCount(),
                "text_chars", doc.text().length()));
    }

    @Transactional
    public void recordFailure(EventEnvelope event, String errorSummary) {
        // Separate ledger name: "the parser failed it" and "the DLQ bookkeeper
        // recorded it" are different consumers of different topics.
        if (!processed.markProcessed(event.eventId(), "parser-dlq")) {
            return;
        }

        Resume resume = resumes.findById(UUID.fromString(event.dataText("resume_id")))
                .orElseThrow(() -> new IllegalStateException("resume row not found for failed parse"));
        resume.markParseFailed();
        resumes.save(resume);

        Application app = applications.findById(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("application not found: " + event.applicationId()));
        app.transitionTo(ApplicationStatus.PARSE_FAILED);
        auditTrail.save(new ApplicationEvent(app.getId(), "PARSE_FAILED", json(Map.of("error", errorSummary))));
        // No outbox event here: the pipeline deliberately ends for this
        // application — a human sees PARSE_FAILED in the dashboard later.
    }

    /** Tiny helper: Map → JSON string for the audit details column. */
    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize audit details", e);
        }
    }
}
