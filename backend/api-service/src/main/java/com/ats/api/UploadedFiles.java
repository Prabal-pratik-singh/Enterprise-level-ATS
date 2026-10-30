package com.ats.api;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ats.config.S3Properties;
import com.ats.util.FileTypes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
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

/**
 * The checks every uploaded resume goes through — an application's (Part 1) and a candidate's own
 * profile resume (Part 2): a short-lived presigned PUT straight to MinIO, then, when the client says
 * "done", the object must exist, be at most 10 MB, and really be a PDF/DOCX/PNG/JPG by its first bytes.
 */
@Component
public class UploadedFiles {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final Duration PRESIGN_TTL = Duration.ofMinutes(10);
    private static final Pattern VERSION_SEGMENT = Pattern.compile("/v(\\d+)/");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "png", "image/png",
            "jpg", "image/jpeg");

    /** A link the client PUTs the file to, valid for PRESIGN_TTL. */
    public record Presigned(String url, long expiresInSeconds) {
    }

    /** What the stored object really is. contentType = what the uploader declared (may be null). */
    public record Inspected(String contentType, long sizeBytes, String detectedType, String sha256) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties s3Props;

    public UploadedFiles(S3Client s3, S3Presigner presigner, S3Properties s3Props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.s3Props = s3Props;
    }

    public Presigned presignPut(String key, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(s3Props.bucket())
                .key(key)
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                b -> b.signatureDuration(PRESIGN_TTL).putObjectRequest(put));
        return new Presigned(presigned.url().toString(), PRESIGN_TTL.toSeconds());
    }

    /** Exists, at most 10 MB, a real PDF/DOCX/PNG/JPG — or a 400 that says which rule failed. */
    public Inspected inspect(String key) {
        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(s3Props.bucket()).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no uploaded object at key — PUT the file first");
        }
        if (head.contentLength() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file exceeds the 10MB limit");
        }
        // Real type comes from the file's first bytes (shared util), never the filename
        String detected = FileTypes.detect(readHeaderBytes(key));
        if (detected == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unrecognized file type — magic bytes must be PDF, DOCX, PNG or JPG");
        }
        return new Inspected(head.contentType(), head.contentLength(), detected, sha256Of(key));
    }

    /** The content type for an accepted file name, or a 400 — callable BEFORE any rows are created. */
    public static String requireSupportedType(String filename) {
        String contentType = CONTENT_TYPES.get(extensionOf(filename));
        if (contentType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "only pdf, docx, png and jpg files are accepted");
        }
        return contentType;
    }

    public static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename with extension is required");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return ext.equals("jpeg") ? "jpg" : ext;
    }

    /** ".../v3/resume.pdf" → 3. */
    public static int versionOf(String key) {
        Matcher m = VERSION_SEGMENT.matcher(key);
        if (!m.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key has no version segment");
        }
        return Integer.parseInt(m.group(1));
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
