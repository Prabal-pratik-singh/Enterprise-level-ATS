package com.ats.fraud;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.ats.domain.ApplicationStatus.EXTRACTED;
import static com.ats.domain.ApplicationStatus.FLAGGED_FOR_REVIEW;
import static com.ats.domain.ApplicationStatus.REJECTED;
import static com.ats.domain.ApplicationStatus.SCORED;
import static com.ats.domain.ApplicationStatus.SHORTLISTED;
import static com.ats.fraud.TrustRecorder.keepsHumanClearance;
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

    private static FraudFlag flag(String code, double weight) {
        return new FraudFlag(code, weight, "x");
    }

    @Test
    void aHumanClearanceSurvivesTheSameEvidence() {
        // cleared with HIDDEN_TEXT on record; a re-check finds HIDDEN_TEXT + a weight-0 note
        assertThat(keepsHumanClearance(true, Set.of("HIDDEN_TEXT"),
                List.of(flag("HIDDEN_TEXT", 0.7), flag("REPEAT_APPLICANT", 0.0)))).isTrue();
    }

    @Test
    void aNewKindOfEvidenceReopensTheReview() {
        assertThat(keepsHumanClearance(true, Set.of("HIDDEN_TEXT"),
                List.of(flag("HIDDEN_TEXT", 0.7), flag("DUPLICATE_FILE", 0.6)))).isFalse();
    }

    @Test
    void withoutAHumanClearanceThereIsNothingToKeep() {
        assertThat(keepsHumanClearance(false, Set.of(), List.of(flag("HIDDEN_TEXT", 0.7)))).isFalse();
    }
}
