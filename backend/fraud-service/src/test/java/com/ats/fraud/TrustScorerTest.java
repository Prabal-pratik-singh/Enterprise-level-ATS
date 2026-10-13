package com.ats.fraud;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustScorerTest {

    /** A fake check that always returns the given flags — lets us test the maths alone. */
    private static TrustScorer scorerReturning(FraudFlag... flags) {
        return new TrustScorer(List.of(input -> List.of(flags)));
    }

    private static FraudFlag flag(double weight) {
        return new FraudFlag("TEST", weight, "test");
    }

    @Test
    void cleanResumeScoresZeroAndCostsNothing() {
        var verdict = scorerReturning().evaluate(null);
        assertThat(verdict.fraudScore()).isZero();
        assertThat(verdict.flagged()).isFalse();
        assertThat(verdict.penalty()).isZero();
    }

    @Test
    void evidencePilesUpButNeverPassesOne() {
        // 1 - (0.5 * 0.5) = 0.75, not 1.0
        assertThat(scorerReturning(flag(0.5), flag(0.5)).evaluate(null).fraudScore()).isEqualTo(0.75);
        // the planted timeline fake: impossible claim 0.5 + throwaway email 0.3 -> 1 - 0.5*0.7 = 0.65
        assertThat(scorerReturning(flag(0.5), flag(0.3)).evaluate(null).fraudScore()).isEqualTo(0.65);
    }

    @Test
    void reachingTheThresholdFlagsAndPenalises() {
        var verdict = scorerReturning(flag(0.7)).evaluate(null);
        assertThat(verdict.flagged()).isTrue();
        assertThat(verdict.penalty()).isEqualTo(35.0); // 0.7 * 50 points
    }

    @Test
    void aSoftSignalAloneIsShownButCostsNoPoints() {
        var verdict = scorerReturning(flag(0.3)).evaluate(null); // e.g. only a throwaway email
        assertThat(verdict.flagged()).isFalse();
        assertThat(verdict.penalty()).isZero();
        assertThat(verdict.flags()).hasSize(1); // still visible to the recruiter
    }
}
