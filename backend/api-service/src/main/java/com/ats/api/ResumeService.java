package com.ats.api;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ats.config.S3Properties;
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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

@Service
public class ResumeService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);
    private static final Pattern VERSION_SEGMENT = Pattern.compile("/v(\\d+)/");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "png", "image/png",
            "jpg", "image/jpeg");

    private final ApplicationRepository applications;
    private final ResumeRepository resumes;
    private final ApplicationEventRepository events;
    private final OutboxPublisher outbox;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties s3Props;

    public ResumeService(ApplicationRepository applications, ResumeRepository resumes,
                         ApplicationEventRepository events, OutboxPublisher outbox,
                         S3Client s3, S3Presigner presigner, S3Properties s3Props) {
        this.applications = applications;
        this.resumes = resumes;
        this.events = events;
        this.outbox = outbox;
        this.s3 = s3;
        this.presigner = presigner;
        this.s3Props = s3Props;
    }

    public record UploadUrl(String url, String key, int version, String contentType, long expiresInSeconds) {
    }

    public UploadUrl createUploadUrl(UUID applicationId, String filename) {
        Application app = findApplication(applicationId);
        String ext = extensionOf(filename);
        String contentType = CONTENT_TYPES.get(ext);
        if (contentType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "only pdf, docx, png and jpg files are accepted");
        }
        int version = resumes.maxVersion(applicationId) + 1;
        String key = "resumes/%s/%s/v%d/resume.%s".formatted(app.getJobId(), applicationId, version, ext);

        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(s3Props.bucket())
                .key(key)
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                b -> b.signatureDuration(PRESIGN_TTL).putObjectRequest(put));
        return new UploadUrl(presigned.url().toString(), key, version, contentType, PRESIGN_TTL.toSeconds());
    }

    @Transactional
    public Resume complete(UUID applicationId, String key) {
        Application app = findApplication(applicationId);
        String expectedPrefix = "resumes/%s/%s/".formatted(app.getJobId(), applicationId);
        if (key == null || !key.startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key does not belong to this application");
        }
        int version = versionOf(key);

        // Idempotent: completing the same upload twice returns the existing row.
        var existing = resumes.findByApplicationIdAndVersion(applicationId, version);
        if (existing.isPresent()) {
            return existing.get();
        }

        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(s3Props.bucket()).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no uploaded object at key — PUT the file first");
        }
        if (head.contentLength() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file exceeds the 10MB limit");
        }
        String detected = detectType(readHeaderBytes(key));
        if (detected == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unrecognized file type — magic bytes must be PDF, DOCX, PNG or JPG");
        }
        String sha256 = sha256Of(key);

        Resume resume = new Resume(applicationId, version, key, head.contentType(), head.contentLength(), sha256);
        resumes.save(resume);
        events.save(new ApplicationEvent(applicationId, "RESUME_UPLOADED",
                "{\"s3_key\":\"%s\",\"sha256\":\"%s\",\"detected_type\":\"%s\"}".formatted(key, sha256, detected)));
        outbox.append(Topics.RESUME_UPLOADED, applicationId, app.getJobId(), Map.of(
                "resume_id", resume.getId().toString(),
                "s3_key", key,
                "version", version,
                "content_type", head.contentType() == null ? "" : head.contentType(),
                "size_bytes", head.contentLength(),
                "sha256", sha256,
                "detected_type", detected));
        return resume;
    }

    private Application findApplication(UUID id) {
        return applications.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found: " + id));
    }

    private static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename with extension is required");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return ext.equals("jpeg") ? "jpg" : ext;
    }

    private static int versionOf(String key) {
        Matcher m = VERSION_SEGMENT.matcher(key);
        if (!m.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key has no version segment");
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * Demo simplification (documented in README): magic-byte allowlist instead
     * of ClamAV. DOCX shares the generic ZIP signature, so any zip passes as
     * "docx" — acceptable here.
     */
    private static String detectType(byte[] h) {
        if (h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') {
            return "pdf";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "png";
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (h.length >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 0x03 && h[3] == 0x04) {
            return "docx";
        }
        return null;
    }

    private byte[] readHeaderBytes(String key) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(s3Props.bucket()).key(key).range("bytes=0-7").build();
        try (ResponseInputStream<?> in = s3.getObject(req)) {
            return in.readNBytes(8);
        } catch (IOException e) {
            throw new IllegalStateException("failed reading object header for " + key, e);
        }
    }

    private String sha256Of(String key) {
        GetObjectRequest req = GetObjectRequest.builder().bucket(s3Props.bucket()).key(key).build();
        try (InputStream in = s3.getObject(req)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed hashing object " + key, e);
        }
    }
}
