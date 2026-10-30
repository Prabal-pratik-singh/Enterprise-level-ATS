package com.ats.candidate;

import java.util.Map;
import java.util.UUID;

import com.ats.api.ResumeService;
import com.ats.api.UploadedFiles;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A resume uploaded to the candidate's OWN profile (not to a job). Same flow and checks as an
 * application's resume — presigned PUT, then "complete" — but stored under profiles/{account}/ and
 * announced on profile.resume.uploaded, so only the parser picks it up.
 */
@Service
public class ProfileResumeService {

    private final ProfileResumeRepository resumes;
    private final UploadedFiles files;
    private final OutboxPublisher outbox;

    public ProfileResumeService(ProfileResumeRepository resumes, UploadedFiles files, OutboxPublisher outbox) {
        this.resumes = resumes;
        this.files = files;
        this.outbox = outbox;
    }

    /** "profiles/{account}/v{n}/resume.pdf" — n counts up per account. */
    public ResumeService.UploadUrl createUploadUrl(UUID userId, String filename) {
        String contentType = UploadedFiles.requireSupportedType(filename);
        int version = resumes.maxVersion(userId) + 1;
        String key = "%sv%d/resume.%s".formatted(folderOf(userId), version, UploadedFiles.extensionOf(filename));
        UploadedFiles.Presigned presigned = files.presignPut(key, contentType);
        return new ResumeService.UploadUrl(presigned.url(), key, version, contentType, presigned.expiresInSeconds());
    }

    @Transactional
    public ProfileResume complete(UUID userId, String key) {
        if (key == null || !key.startsWith(folderOf(userId))) {   // only ever inside the caller's own folder
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key does not belong to your profile");
        }
        int version = UploadedFiles.versionOf(key);
        var existing = resumes.findByUserIdAndVersion(userId, version);
        if (existing.isPresent()) {
            return existing.get();   // completing the same upload twice is harmless
        }

        UploadedFiles.Inspected file = files.inspect(key);   // exists, ≤10 MB, real type by magic bytes, sha256
        ProfileResume resume = resumes.save(
                new ProfileResume(userId, version, key, file.contentType(), file.sizeBytes(), file.sha256()));
        outbox.appendForSubject(Topics.PROFILE_RESUME_UPLOADED, EventEnvelope.PROFILE, userId, Map.of(
                "profile_resume_id", resume.getId().toString(),
                "s3_key", key,
                "version", version,
                "content_type", file.contentType() == null ? "" : file.contentType(),
                "size_bytes", file.sizeBytes(),
                "sha256", file.sha256(),
                "detected_type", file.detectedType()));
        return resume;
    }

    static String folderOf(UUID userId) {
        return "profiles/%s/".formatted(userId);
    }
}
