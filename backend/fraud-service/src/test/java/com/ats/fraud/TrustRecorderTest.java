package com.ats.fraud;

import org.junit.jupiter.api.Test;

import static com.ats.domain.ApplicationStatus.EXTRACTED;
import static com.ats.domain.ApplicationStatus.FLAGGED_FOR_REVIEW;
import static com.ats.domain.ApplicationStatus.REJECTED;
import static com.ats.domain.ApplicationStatus.SCORED;
import static com.ats.domain.ApplicationStatus.SHORTLISTED;
import static com.ats.fraud.TrustRecorder.nextStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** The status rules — the part of the Trust Service that must never overrule a human. */
class TrustRecorderTest {

    @Test
    void aFlagSendsAScoredApplicationToReview() {
        assertThat(nextStatus(SCORED, true, true)).isEqualTo(FLAGGED_FOR_REVIEW);
        assertThat(nextStatus(EXTRACTED, true, false)).isEqualTo(FLAGGED_FOR_REVIEW); // matcher not done yet
    }

    @Test
    void aFlagNeverOverridesAHumanDecision() {
        assertThat(nextStatus(SHORTLISTED, true, true)).isEqualTo(SHORTLISTED);
        assertThat(nextStatus(REJECTED, true, true)).isEqualTo(REJECTED);
    }

    @Test
    void aCleanRecheckReleasesAnEarlierFlag() {
        assertThat(nextStatus(FLAGGED_FOR_REVIEW, false, true)).isEqualTo(SCORED);
        assertThat(nextStatus(FLAGGED_FOR_REVIEW, false, false)).isEqualTo(EXTRACTED);
    }

    @Test
    void aCleanResultLeavesThePipelineAlone() {
        assertThat(nextStatus(EXTRACTED, false, false)).isEqualTo(EXTRACTED); // the matcher moves it on
        assertThat(nextStatus(SCORED, false, true)).isEqualTo(SCORED);
    }
}
