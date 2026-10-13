package com.ats.fraud;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Runs every check and turns the flags into ONE fraud score (0..1) plus the
 * points to subtract from the final score. It only ever FLAGS — a human decides.
 */
@Component
public class TrustScorer {

    static final double FLAG_THRESHOLD = 0.5; // spec: fraud_score >= 0.5 -> FLAGGED_FOR_REVIEW
    static final double MAX_PENALTY = 50.0;   // a certain fraud loses up to 50 of its 100 points

    /** The Trust Service's answer for one application. */
    public record TrustVerdict(double fraudScore, double penalty, boolean flagged, List<FraudFlag> flags) {
    }

    private final List<TrustCheck> checks;

    public TrustScorer(List<TrustCheck> checks) { // Spring hands us every TrustCheck bean
        this.checks = checks;
    }

    public TrustVerdict evaluate(TrustInput input) {
        List<FraudFlag> flags = new ArrayList<>();
        for (TrustCheck check : checks) {
            flags.addAll(check.check(input));
        }
        // "Noisy-OR": each flag on its own leaves a (1 - weight) chance the resume is honest;
        // it is honest overall only if EVERY flag is a false alarm. Evidence piles up, but the
        // score never passes 1 — two 0.5 signals give 0.75, not 1.0.
        double honest = 1.0;
        for (FraudFlag flag : flags) {
            honest *= 1.0 - flag.weight();
        }
        double fraudScore = Math.round((1.0 - honest) * 100) / 100.0;
        boolean flagged = fraudScore >= FLAG_THRESHOLD;
        // Soft signals alone (e.g. only a throwaway email) are shown but cost no points.
        double penalty = flagged ? Math.round(fraudScore * MAX_PENALTY * 10) / 10.0 : 0.0;
        return new TrustVerdict(fraudScore, penalty, flagged, List.copyOf(flags));
    }
}
