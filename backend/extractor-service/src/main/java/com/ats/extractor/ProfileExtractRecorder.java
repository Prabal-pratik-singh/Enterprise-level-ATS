package com.ats.extractor;

import java.util.Map;
import java.util.UUID;

import com.ats.domain.MasterProfile;
import com.ats.domain.MasterProfileRepository;
import com.ats.domain.ProfileResume;
import com.ats.domain.ProfileResumeRepository;
import com.ats.events.EventEnvelope;
import com.ats.events.OutboxPublisher;
import com.ats.events.ProcessedEvents;
import com.ats.events.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction at the end of a profile-resume extraction: ledger mark + the resume's status +
 * the candidate's master profile + profile.changed for the embedder. Two rules protect the candidate:
 * their own fixes (profile_overrides) are never touched, and an older version's late answer never
 * replaces a newer version's profile.
 */
@Service
public class ProfileExtractRecorder {

    static final String EXTRACT_FAILED_MESSAGE =
            "We couldn't turn your resume into a profile right now — please try again in a few minutes.";

    private final ProcessedEvents processed;
    private final ProfileResumeRepository resumes;
    private final MasterProfileRepository profiles;
    private final OutboxPublisher outbox;

    public ProfileExtractRecorder(ProcessedEvents processed, ProfileResumeRepository resumes,
                                  MasterProfileRepository profiles, OutboxPublisher outbox) {
        this.processed = processed;
        this.resumes = resumes;
        this.profiles = profiles;
        this.outbox = outbox;
    }

    @Transactional
    public void recordSuccess(EventEnvelope event, ProfileExtractor.ExtractedProfile result) {
        if (!processed.markProcessed(event.eventId(), ProfileResumeParsedConsumer.CONSUMER_NAME)) {
            return;   // another extractor instance won the race — drop our copy
        }
        ProfileResume resume = resumeOf(event);
        resume.markExtracted();   // this version was read, whatever happens next
        resumes.save(resume);

        MasterProfile profile = profiles.findById(resume.getUserId())
                .orElseGet(() -> new MasterProfile(resume.getUserId()));
        if (currentVersion(profile) > resume.getVersion()) {
            return;   // e.g. v1's slow answer arrived after v2's: the newer profile stays
        }
        profile.replaceExtracted(resume.getId(), result.profile().toString(), result.completeness());
        profiles.save(profile);
        outbox.appendForSubject(Topics.PROFILE_CHANGED, EventEnvelope.PROFILE, resume.getUserId(), Map.of(
                "reason", "extracted",
                "profile_resume_id", resume.getId().toString(),
                "version", resume.getVersion(),
                "completeness", result.completeness()));
    }

    /** DLQ bookkeeping: the page shows a sentence the candidate can act on. */
    @Transactional
    public void recordFailure(EventEnvelope event) {
        if (!processed.markProcessed(event.eventId(), "extractor-profile-dlq")) {
            return;
        }
        ProfileResume resume = resumeOf(event);
        resume.markFailed("EXTRACT_FAILED", EXTRACT_FAILED_MESSAGE);
        resumes.save(resume);
    }

    private ProfileResume resumeOf(EventEnvelope event) {
        UUID id = UUID.fromString(event.dataText("profile_resume_id"));
        return resumes.findById(id).orElseThrow(() -> new IllegalStateException("profile resume not found: " + id));
    }

    /** The version the profile currently shows; -1 when nothing was extracted yet. */
    private int currentVersion(MasterProfile profile) {
        return profile.getCurrentResumeId() == null ? -1
                : resumes.findById(profile.getCurrentResumeId()).map(ProfileResume::getVersion).orElse(-1);
    }
}
