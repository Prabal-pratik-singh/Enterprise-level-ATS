package com.ats.candidate;

import java.util.Optional;
import java.util.UUID;

import com.ats.api.ResumeService;
import com.ats.api.UploadedFiles;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A resume uploaded to the candidate's own profile: their folder only, the next version, the parser told. */
class ProfileResumeServiceTest {

    private final ProfileResumeRepository resumes = mock(ProfileResumeRepository.class);
    private final UploadedFiles files = mock(UploadedFiles.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final ProfileResumeService service = new ProfileResumeService(resumes, files, outbox);
    private final UUID sneha = UUID.randomUUID();   // her account id, as it comes from her token

    @Test
    void theUploadLinkPointsIntoHerOwnFolderWithTheNextVersion() {
        when(resumes.maxVersion(sneha)).thenReturn(1);   // she uploaded once before
        when(files.presignPut(anyString(), anyString())).thenReturn(new UploadedFiles.Presigned("http://minio/put", 600));

        ResumeService.UploadUrl url = service.createUploadUrl(sneha, "Sneha Singh CV.PDF");

        assertThat(url.key()).isEqualTo("profiles/" + sneha + "/v2/resume.pdf");
        assertThat(url.version()).isEqualTo(2);
        assertThat(url.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void anUnsupportedFileIsRefusedBeforeAnyLinkIsMade() {
        assertThatThrownBy(() -> service.createUploadUrl(sneha, "setup.exe")).hasMessageContaining("only pdf, docx");
        verifyNoInteractions(files);
    }

    @Test
    void aKeyFromSomeoneElsesFolderIsRefused() {
        String ravisKey = "profiles/" + UUID.randomUUID() + "/v1/resume.pdf";

        assertThatThrownBy(() -> service.complete(sneha, ravisKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("key does not belong to your profile");
        verifyNoInteractions(files, outbox);
    }

    @Test
    void completingRecordsTheResumeAndTellsOnlyTheProfilePipeline() {
        String key = "profiles/" + sneha + "/v1/resume.pdf";
        when(files.inspect(key)).thenReturn(new UploadedFiles.Inspected("application/pdf", 48_213L, "pdf", "ab12cd"));
        when(resumes.save(any(ProfileResume.class))).thenAnswer(call -> call.getArgument(0));

        ProfileResume resume = service.complete(sneha, key);

        assertThat(resume.getStatus()).isEqualTo("UPLOADED");
        assertThat(resume.getVersion()).isEqualTo(1);
        assertThat(resume.getSha256()).isEqualTo("ab12cd");
        verify(outbox).appendForSubject(eq(Topics.PROFILE_RESUME_UPLOADED), eq(EventEnvelope.PROFILE), eq(sneha), anyMap());
    }

    @Test
    void completingTwiceReturnsTheSameRow() {
        String key = "profiles/" + sneha + "/v1/resume.pdf";
        ProfileResume first = new ProfileResume(sneha, 1, key, "application/pdf", 48_213L, "ab12cd");
        when(resumes.findByUserIdAndVersion(sneha, 1)).thenReturn(Optional.of(first));

        assertThat(service.complete(sneha, key)).isSameAs(first);
        verifyNoInteractions(files, outbox);
    }
}
