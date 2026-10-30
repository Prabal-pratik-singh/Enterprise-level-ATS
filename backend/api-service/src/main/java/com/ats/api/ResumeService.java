package com.ats.api;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.Application;
import com.ats.domain.ApplicationEvent;
import com.ats.domain.ApplicationEventRepository;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.Resume;
import com.ats.domain.ResumeRepository;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** An application's resume: presigned upload, then "complete" records it and announces resume.uploaded. */
@Service
public class ResumeService {

    private final ApplicationRepository applications;
    private final ResumeRepository resumes;
    private final ApplicationEventRepository events;
    private final OutboxPublisher outbox;
    private final UploadedFiles files;   // presign + the size / magic-bytes / sha256 checks (shared with profile resumes)

    public ResumeService(ApplicationRepository applications, ResumeRepository resumes,
                         ApplicationEventRepository events, OutboxPublisher outbox, UploadedFiles files) {
        this.applications = applications;
        this.resumes = resumes;
        this.events = events;
        this.outbox = outbox;
        this.files = files;
    }

    public record UploadUrl(String url, String key, int version, String contentType, long expiresInSeconds) {
    }

    public UploadUrl createUploadUrl(UUID applicationId, String filename) {
        Application app = findApplication(applicationId);
        String ext = UploadedFiles.extensionOf(filename);
        String contentType = requireSupportedType(filename);
        int version = resumes.maxVersion(applicationId) + 1;
        String key = "resumes/%s/%s/v%d/resume.%s".formatted(app.getJobId(), applicationId, version, ext);
        UploadedFiles.Presigned presigned = files.presignPut(key, contentType);
        return new UploadUrl(presigned.url(), key, version, contentType, presigned.expiresInSeconds());
    }

    @Transactional
    public Resume complete(UUID applicationId, String key) {
        Application app = findApplication(applicationId);
        String expectedPrefix = "resumes/%s/%s/".formatted(app.getJobId(), applicationId);
        if (key == null || !key.startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key does not belong to this application");
        }
        int version = UploadedFiles.versionOf(key);

        // Idempotent: completing the same upload twice returns the existing row.
        var existing = resumes.findByApplicationIdAndVersion(applicationId, version);
        if (existing.isPresent()) {
            return existing.get();
        }

        UploadedFiles.Inspected file = files.inspect(key);   // exists, ≤10 MB, real type by magic bytes, sha256

        Resume resume = new Resume(applicationId, version, key, file.contentType(), file.sizeBytes(), file.sha256());
        resumes.save(resume);
        events.save(new ApplicationEvent(applicationId, "RESUME_UPLOADED",
                "{\"s3_key\":\"%s\",\"sha256\":\"%s\",\"detected_type\":\"%s\"}".formatted(key, file.sha256(),
                        file.detectedType())));
        outbox.append(Topics.RESUME_UPLOADED, applicationId, app.getJobId(), Map.of(
                "resume_id", resume.getId().toString(),
                "s3_key", key,
                "version", version,
                "content_type", file.contentType() == null ? "" : file.contentType(),
                "size_bytes", file.sizeBytes(),
                "sha256", file.sha256(),
                "detected_type", file.detectedType()));
        return resume;
    }

    private Application findApplication(UUID id) {
        return applications.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found: " + id));
    }

    /** The content type for an accepted file name, or a 400 — callable BEFORE any rows are created. */
    public static String requireSupportedType(String filename) {
        return UploadedFiles.requireSupportedType(filename);
    }
}
