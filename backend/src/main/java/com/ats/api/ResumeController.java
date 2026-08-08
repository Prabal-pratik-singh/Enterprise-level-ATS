package com.ats.api;

import java.util.UUID;

import com.ats.domain.Resume;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications/{id}/resume")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    public record UploadUrlRequest(String filename) {
    }

    public record CompleteRequest(String key) {
    }

    public record ResumeResponse(UUID id, UUID applicationId, int version, String key,
                                 String contentType, Long sizeBytes, String sha256, String parseStatus) {
    }

    @PostMapping("/upload-url")
    public ResumeService.UploadUrl uploadUrl(@PathVariable UUID id, @RequestBody UploadUrlRequest req) {
        return resumeService.createUploadUrl(id, req.filename());
    }

    @PostMapping("/complete")
    public ResumeResponse complete(@PathVariable UUID id, @RequestBody CompleteRequest req) {
        Resume r = resumeService.complete(id, req.key());
        return new ResumeResponse(r.getId(), r.getApplicationId(), r.getVersion(), r.getS3Key(),
                r.getContentType(), r.getSizeBytes(), r.getSha256(), r.getParseStatus());
    }
}
