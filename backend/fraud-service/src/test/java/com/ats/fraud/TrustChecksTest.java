package com.ats.fraud;

import java.time.YearMonth;
import java.util.List;

import com.ats.taxonomy.SkillTaxonomy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One block of tests per check. Every check gets at least one "catches the fake"
 * test AND one "leaves an honest resume alone" test — a fraud detector that
 * cries wolf is as useless as one that sleeps.
 */
class TrustChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SkillTaxonomy TAXONOMY = new SkillTaxonomy(MAPPER);
    private static final YearMonth TODAY = YearMonth.of(2026, 10); // pinned "now" keeps tests deterministic

    private static JsonNode profile(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static TrustInput input(JsonNode profile) {
        return new TrustInput(profile, "someone@gmail.com", null, TODAY);
    }

    @Nested
    class Timeline {

        private final TimelineCheck check = new TimelineCheck(TAXONOMY);

        @Test
        void twoFullTimeJobsInParallelAreFlagged() throws Exception {
            // CloudSprint and OrbitPay share 2022-01..2024-06 = 30 months (the planted fake's shape)
            var flags = check.check(input(profile("""
                    {"experience":[
                      {"title":"Senior Software Engineer","company":"CloudSprint","start_date":"2021-03","end_date":"2024-06"},
                      {"title":"Backend Developer","company":"OrbitPay","start_date":"2022-01","end_date":"2025-05"}]}""")));
            assertThat(flags).extracting(FraudFlag::code).containsExactly("OVERLAPPING_JOBS");
            assertThat(flags.get(0).explanation()).contains("30 months", "CloudSprint", "OrbitPay");
        }

        @Test
        void shortNoticePeriodOverlapIsNormal() throws Exception {
            // 2 months of overlap while serving notice — happens all the time
            assertThat(check.check(input(profile("""
                    {"experience":[
                      {"title":"Engineer","company":"A","start_date":"2020-01","end_date":"2022-03"},
                      {"title":"Engineer","company":"B","start_date":"2022-02","end_date":"2024-01"}]}"""))))
                    .isEmpty();
        }

        @Test
        void internshipDuringAJobIsNotCounted() throws Exception {
            assertThat(check.check(input(profile("""
                    {"experience":[
                      {"title":"Software Engineer","company":"A","start_date":"2020-01","end_date":"2024-01"},
                      {"title":"Freelance Developer","company":"Self","start_date":"2021-01","end_date":"2023-01"}]}"""))))
                    .isEmpty();
        }

        @Test
        void claimingMoreYearsThanSinceGraduationIsFlagged() throws Exception {
            // graduated 2019 -> ~7 years by 2026-10, yet claims 8+ (the planted fake's exact text)
            var flags = check.check(input(profile("""
                    {"summary":"Platform engineer with 8+ years of hands-on production Kubernetes experience.",
                     "education":[{"degree":"B.Tech","year":2019}]}""")));
            assertThat(flags).extracting(FraudFlag::code).containsExactly("IMPOSSIBLE_EXPERIENCE");
            assertThat(flags.get(0).explanation()).contains("8 years of kubernetes", "2019");
        }

        @Test
        void roundingUpIsForgiven() throws Exception {
            // graduated 2019: 7 years 4 months by today — "7 years" is honest, not a lie
            assertThat(check.check(input(profile("""
                    {"summary":"Engineer with 7 years of experience building java systems.",
                     "education":[{"degree":"B.Tech","year":2019}]}"""))))
                    .isEmpty();
        }

        @Test
        void futureGraduationOfAFresherProvesNothing() throws Exception {
            assertThat(check.check(input(profile("""
                    {"summary":"Student with 1 year of internship experience.",
                     "education":[{"degree":"B.Tech","year":2027}]}"""))))
                    .isEmpty();
        }
    }

    @Nested
    class Anachronism {

        private final AnachronismCheck check = new AnachronismCheck(TAXONOMY);

        @Test
        void jobThatEndedBeforeTheToolExistedIsFlagged() throws Exception {
            // Kubernetes was first released in 2014; this job ended in 2013
            var flags = check.check(input(profile("""
                    {"experience":[{"title":"DevOps Engineer","company":"OldCo","start_date":"2010-01","end_date":"2013-05",
                      "description":"Ran kubernetes-based deployments for 40 services."}]}""")));
            assertThat(flags).extracting(FraudFlag::code).containsExactly("TECH_ANACHRONISM");
            assertThat(flags.get(0).explanation()).contains("kubernetes", "2014", "OldCo");
        }

        @Test
        void jobThatStartedEarlierButRanIntoTheToolIsFine() throws Exception {
            // started 2010, ended 2016 — they may well have adopted Kubernetes in 2015
            assertThat(check.check(input(profile("""
                    {"experience":[{"title":"DevOps Engineer","company":"OldCo","start_date":"2010-01","end_date":"2016-05",
                      "description":"Moved our services to Kubernetes."}]}"""))))
                    .isEmpty();
        }

        @Test
        void claimingMoreYearsThanTheToolHasExistedIsFlagged() throws Exception {
            // 15 years of Kubernetes in 2026 = since 2011, three years before it existed
            var flags = check.check(input(profile("""
                    {"summary":"Architect with 15 years of Kubernetes experience."}""")));
            assertThat(flags).extracting(FraudFlag::code).containsExactly("TECH_ANACHRONISM");
            assertThat(flags.get(0).explanation()).contains("since 2011", "2014");
        }

        @Test
        void generalClaimIsNotPinnedOnTheSkillsAfterIt() throws Exception {
            // "12 years of experience building ... kubernetes" is about the career, not about Kubernetes
            assertThat(check.check(input(profile("""
                    {"summary":"Engineer with 13 years of experience building redis and kubernetes systems."}"""))))
                    .isEmpty();
        }

        @Test
        void shortEverydayWordsAreNotMistakenForSkills() throws Exception {
            // "go" is also the Go language (2009) — "go live" in a 2008 job must not count
            assertThat(check.check(input(profile("""
                    {"experience":[{"title":"Engineer","company":"A","start_date":"2005-01","end_date":"2008-06",
                      "description":"Helped the billing system go live on time."}]}"""))))
                    .isEmpty();
        }
    }

    @Nested
    class HiddenText {

        private final HiddenTextCheck check = new HiddenTextCheck();

        private List<FraudFlag> run(int tiny, int white) throws Exception {
            var stats = new TrustInput.HiddenTextStats(1500, tiny, white, "kuberneteskafkaaws");
            return check.check(new TrustInput(profile("{}"), "a@gmail.com", stats, TODAY));
        }

        @Test
        void keywordStuffingIsFlagged() throws Exception {
            var flags = run(378, 378);
            assertThat(flags).extracting(FraudFlag::code).containsExactly("HIDDEN_TEXT");
            assertThat(flags.get(0).explanation()).contains("378", "tiny white text", "kuberneteskafkaaws");
        }

        @Test
        void tinyAndWhiteAreTheSameLettersNotDouble() throws Exception {
            assertThat(run(378, 378).get(0).explanation()).startsWith("378 characters");
        }

        @Test
        void aFewTinyCharactersAreJustAFooter() throws Exception {
            assertThat(run(6, 0)).isEmpty(); // e.g. a 3pt page number
        }

        @Test
        void noFingerprintMeansNoVerdict() throws Exception {
            assertThat(check.check(input(profile("{}")))).isEmpty(); // DOCX/image resume
        }
    }

    @Nested
    class DisposableEmail {

        private final DisposableEmailCheck check = new DisposableEmailCheck();

        @Test
        void throwawayInboxIsFlagged() throws Exception {
            var flags = check.check(new TrustInput(profile("{}"), "rohan@mailinator.com", null, TODAY));
            assertThat(flags).extracting(FraudFlag::code).containsExactly("DISPOSABLE_EMAIL");
        }

        @Test
        void throwawayEmailPrintedOnTheResumeAlsoCounts() throws Exception {
            var flags = check.check(new TrustInput(profile("""
                    {"contact":{"email":"x@temp.yopmail.com"}}"""), "real@gmail.com", null, TODAY));
            assertThat(flags).hasSize(1); // subdomain of a listed provider
        }

        @Test
        void normalProvidersAreFine() throws Exception {
            assertThat(check.check(new TrustInput(profile("{}"), "priya@outlook.com", null, TODAY))).isEmpty();
        }
    }
}
