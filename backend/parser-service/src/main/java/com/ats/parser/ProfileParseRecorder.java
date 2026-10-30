package com.ats.parser;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction at the end of a profile-resume parse: ledger mark + the resume's status +
 * the outbox event for the extractor. On failure (from the DLQ) the candidate gets a sentence they
 * can act on; the technical cause stays in the logs.
 */
@Service
public class ProfileParseRecorder {

    static final String PARSE_FAILED_MESSAGE =
            "We couldn't read this file — if it's a scan, try a clearer copy or a PDF with selectable text.";

    private final ProcessedEvents processed;
    private final ProfileResumeRepository resumes;
    private final OutboxPublisher outbox;

    public ProfileParseRecorder(ProcessedEvents processed, ProfileResumeRepository resumes, OutboxPublisher outbox) {
        this.processed = processed;
        this.resumes = resumes;
        this.outbox = outbox;
    }

    @Transactional
    public void recordSuccess(EventEnvelope event, ProfileResume resume, ParsedDocument doc, String parsedKey) {
        if (!processed.markProcessed(event.eventId(), ProfileResumeUploadedConsumer.CONSUMER_NAME)) {
            return;   // another parser instance won the race — drop our copy
        }
        resume.markParsed(parsedKey);   // loaded outside this transaction (detached) → save() below
        resumes.save(resume);
        outbox.appendForSubject(Topics.PROFILE_RESUME_PARSED, EventEnvelope.PROFILE, resume.getUserId(), Map.of(
                "profile_resume_id", resume.getId().toString(),
                "version", resume.getVersion(),
                "parsed_s3_key", parsedKey,
                "parse_method", doc.parseMethod(),
                "text_chars", doc.text().length()));
    }

    /**
     * DLQ bookkeeping: the pipeline ends here for this version; the page shows the plain message.
     * profile.changed (reason parse_failed) lets an open page update live — the embedder ignores it.
     */
    @Transactional
    public void recordFailure(EventEnvelope event) {
        if (!processed.markProcessed(event.eventId(), "parser-profile-dlq")) {
            return;
        }
        ProfileResume resume = resumes.findById(UUID.fromString(event.dataText("profile_resume_id")))
                .orElseThrow(() -> new IllegalStateException("profile resume not found for a failed parse"));
        resume.markFailed("PARSE_FAILED", PARSE_FAILED_MESSAGE);
        resumes.save(resume);
        outbox.appendForSubject(Topics.PROFILE_CHANGED, EventEnvelope.PROFILE, resume.getUserId(), Map.of(
                "reason", "parse_failed",
                "profile_resume_id", resume.getId().toString(),
                "version", resume.getVersion()));
    }
}
