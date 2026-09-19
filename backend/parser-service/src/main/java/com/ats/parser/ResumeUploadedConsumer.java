package com.ats.parser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ats.config.S3Properties;
import com.ats.domain.Resume;
import com.ats.domain.ResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import com.ats.util.FileTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The parser worker's front door. Listens to resume.uploaded, turns the file
 * into text + layout metadata, stores parsed.json in MinIO, then hands off to
 * ParseRecorder for the one-transaction bookkeeping. Any exception that
 * escapes handle() triggers the shared retry→DLQ policy — that is not an
 * accident, it IS the error handling.
 */
@Component
public class ResumeUploadedConsumer {

    /** Our name in the processed_events ledger (each consumer type gets its own). */
    static final String CONSUMER_NAME = "parser";

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadedConsumer.class);

    private final ObjectMapper mapper;
    private final ProcessedEvents processed;
    private final ResumeRepository resumes;
    private final S3Client s3;
    private final S3Properties s3Props;
    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;
    private final OcrEngine ocrEngine;
    private final ParseRecorder recorder;

    public ResumeUploadedConsumer(ObjectMapper mapper, ProcessedEvents processed, ResumeRepository resumes,
                                  S3Client s3, S3Properties s3Props, PdfExtractor pdfExtractor,
                                  DocxExtractor docxExtractor, OcrEngine ocrEngine, ParseRecorder recorder) {
        this.mapper = mapper;
        this.processed = processed;
        this.resumes = resumes;
        this.s3 = s3;
        this.s3Props = s3Props;
        this.pdfExtractor = pdfExtractor;
        this.docxExtractor = docxExtractor;
        this.ocrEngine = ocrEngine;
        this.recorder = recorder;
    }

    // group-id comes from application.yml (parser-service); 12 partitions on the
    // topic mean up to 12 of these containers can share the work.
    @KafkaListener(topics = Topics.RESUME_UPLOADED)
    public void handle(String message) throws Exception {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);

        // Duplicate delivery? (outbox re-send, rebalance, crash-replay) — skip
        // BEFORE the expensive download/OCR work.
        if (processed.alreadyProcessed(event.eventId(), CONSUMER_NAME)) {
            log.info("skipping duplicate event {}", event.eventId());
            return;
        }

        UUID resumeId = UUID.fromString(event.dataText("resume_id"));
        String s3Key = event.dataText("s3_key");
        // The outbox row commits AFTER the resume row, so this lookup can only
        // fail if something is seriously wrong — failing loudly → retry → DLQ.
        Resume resume = resumes.findById(resumeId)
                .orElseThrow(() -> new IllegalStateException("resume row not found: " + resumeId));

        // Pull the actual bytes from MinIO (max 10MB — fits comfortably in memory)
        byte[] fileBytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(s3Props.bucket()).key(s3Key).build()).asByteArray();

        // Route by the REAL type (first bytes), not by the filename in the key
        String type = FileTypes.detect(fileBytes);
        ParsedDocument parsedDoc = switch (type == null ? "unknown" : type) {
            case "pdf" -> pdfExtractor.extract(fileBytes);
            case "docx" -> docxExtractor.extract(fileBytes);
            case "png", "jpg" -> {
                OcrEngine.OcrResult result = ocrEngine.ocrImage(fileBytes);
                yield ParsedDocument.imageOcr(result.text(), result.meanConfidence());
            }
            default -> throw new IllegalStateException("unsupported file type: " + type);
        };

        // parsed.json lives right next to the original file:
        //   resumes/{job}/{app}/v1/resume.pdf  →  resumes/{job}/{app}/v1/parsed.json
        // Written BEFORE the DB transaction: if the tx fails and we retry,
        // overwriting the same JSON is harmless (idempotent).
        String parsedKey = s3Key.substring(0, s3Key.lastIndexOf('/') + 1) + "parsed.json";
        s3.putObject(
                PutObjectRequest.builder().bucket(s3Props.bucket()).key(parsedKey)
                        .contentType("application/json").build(),
                RequestBody.fromBytes(parsedJson(event, resume, parsedDoc)));

        // One transaction: ledger mark + resume row + app status + audit + outbox
        recorder.recordSuccess(event, resume, parsedDoc, parsedKey);
        log.info("parsed application {} via {} ({} chars, {} page(s))",
                event.applicationId(), parsedDoc.parseMethod(), parsedDoc.text().length(), parsedDoc.pageCount());
    }

    /** parsed.json carries everything downstream stages need — they never reopen the PDF. */
    private byte[] parsedJson(EventEnvelope event, Resume resume, ParsedDocument doc) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>(); // LinkedHashMap keeps the field order readable
        body.put("application_id", event.applicationId());
        body.put("resume_id", resume.getId());
        body.put("source_s3_key", resume.getS3Key());
        body.put("parse_method", doc.parseMethod());
        body.put("page_count", doc.pageCount());
        body.put("ocr_confidence", doc.ocrConfidence());
        body.put("layout", doc.layout()); // font/color fingerprint — Phase 6 fraud evidence
        body.put("text", doc.text());
        body.put("parsed_at", Instant.now().toString());
        return mapper.writeValueAsBytes(body);
    }
}
