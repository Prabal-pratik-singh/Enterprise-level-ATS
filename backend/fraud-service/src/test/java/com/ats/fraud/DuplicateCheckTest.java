package com.ats.fraud;

import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateCheckTest {

    private final DuplicateCheck check = new DuplicateCheck();

    private List<FraudFlag> run(TrustInput.DuplicateMatch... matches) throws Exception {
        return check.check(new TrustInput(new ObjectMapper().readTree("{}"), "a@gmail.com", null,
                YearMonth.of(2026, 10), List.of(matches)));
    }

    private static TrustInput.DuplicateMatch match(String kind, double similarity, String text) {
        return new TrustInput.DuplicateMatch(kind, similarity, text);
    }

    @Test
    void eachKindCostsItsWeight() throws Exception {
        var flags = run(match("DUPLICATE_FILE", 1.0, "same file"), match("COPIED_CONTENT", 0.9, "copied"),
                match("REPEAT_APPLICANT", 1.0, "same person"));
        assertThat(flags).extracting(FraudFlag::code, FraudFlag::weight).containsExactly(
                org.assertj.core.groups.Tuple.tuple("DUPLICATE_FILE", 0.6),
                org.assertj.core.groups.Tuple.tuple("COPIED_CONTENT", 0.5),
                org.assertj.core.groups.Tuple.tuple("REPEAT_APPLICANT", 0.0));
    }

    @Test
    void manyCopiesAreOneFlagWithTheStrongestSentence() throws Exception {
        var flags = run(match("COPIED_CONTENT", 0.75, "75% like Priya"), match("COPIED_CONTENT", 0.92, "92% like Rahul"),
                match("COPIED_CONTENT", 0.80, "80% like Neha"));
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).explanation()).isEqualTo("92% like Rahul (and 2 more)");
    }

    @Test
    void aRepeatApplicantAloneCostsNothing() throws Exception {
        var verdict = new TrustScorer(List.of(check)).evaluate(new TrustInput(new ObjectMapper().readTree("{}"),
                "a@gmail.com", null, YearMonth.of(2026, 10), List.of(match("REPEAT_APPLICANT", 1.0, "same person"))));
        assertThat(verdict.flagged()).isFalse();
        assertThat(verdict.penalty()).isZero();
        assertThat(verdict.flags()).hasSize(1); // still shown as a note
    }

    @Test
    void noDedupResultsMeansNoFlags() throws Exception {
        assertThat(run()).isEmpty();
    }
}
