package com.ats.dedup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ats.dedup.DuplicateFinder.Applicant;
import com.ats.dedup.DuplicateFinder.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateFinderTest {

    private final MinHash minHash = new MinHash();

    private Applicant applicant(String name, String email, String phone, String sha, String text, Instant at) {
        UUID id = UUID.randomUUID();
        return new Applicant(id, name, email, phone, sha, minHash.signature(MinHash.shingles(text)),
                at == null ? null : id, at); // an earlier application is its own cluster
    }

    // a realistic work history: three roles, ~120 words (real profiles here have 49–148 shingles)
    private static final String TEXT = MinHashTest.RESUME
            + " Software Engineer at CloudSprint. Owned the order service end to end, wrote the PostgreSQL schema "
            + "migrations, added contract tests between six teams and moved nightly batch jobs onto Kubernetes cron. "
            + "Junior Developer at TechNova Solutions. Fixed production bugs in the billing module, wrote REST "
            + "endpoints in Java, and automated the release checklist with Jenkins pipelines.";
    private static final String OTHER_TEXT = "Frontend developer at PixelCraft. Designed accessible React "
            + "component libraries, wrote Storybook docs and ran usability studies every quarter.";

    @Test
    void samePersonReapplyingIsANoteNotFraud() {
        var earlier = applicant("Priya Iyer", "priya@gmail.com", "+91-98765 43210", "abc", TEXT, Instant.EPOCH);
        var me = applicant("Priya Iyer", "PRIYA@gmail.com ", "9876543210", "abc", TEXT, null);
        var result = DuplicateFinder.find(me, List.of(earlier));
        assertThat(result.findings()).extracting(f -> f.kind()).containsExactly(Kind.REPEAT_APPLICANT);
        assertThat(result.findings().get(0).sameFile()).isTrue();
    }

    @Test
    void sameFileUnderAnotherNameIsADuplicateFile() {
        var original = applicant("Priya Iyer", "priya@gmail.com", "9876543210", "abc", TEXT, Instant.EPOCH);
        var me = applicant("Rahul Verma", "rahul@yahoo.com", "9123456780", "abc", TEXT, null);
        var result = DuplicateFinder.find(me, List.of(original));
        assertThat(result.findings()).extracting(f -> f.kind()).containsExactly(Kind.DUPLICATE_FILE);
        assertThat(result.clusterId()).isEqualTo(original.clusterId()); // joins the original's cluster
    }

    @Test
    void copiedWorkHistoryWithSmallEditsIsCaught() {
        String edited = TEXT.replace("four engineers", "five engineers").replace("forty percent", "half");
        var original = applicant("Priya Iyer", "priya@gmail.com", "9876543210", "abc", TEXT, Instant.EPOCH);
        var me = applicant("Rahul Verma", "rahul@yahoo.com", "9123456780", "xyz", edited, null);
        var result = DuplicateFinder.find(me, List.of(original));
        assertThat(result.findings()).extracting(f -> f.kind()).containsExactly(Kind.COPIED_CONTENT);
        assertThat(result.findings().get(0).similarity()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void unrelatedStrangersProduceNothing() {
        var other = applicant("Priya Iyer", "priya@gmail.com", "9876543210", "abc", OTHER_TEXT, Instant.EPOCH);
        var me = applicant("Rahul Verma", "rahul@yahoo.com", "9123456780", "xyz", TEXT, null);
        var result = DuplicateFinder.find(me, List.of(other));
        assertThat(result.findings()).isEmpty();
        assertThat(result.clusterId()).isEqualTo(me.applicationId()); // a cluster of one
    }

    @Test
    void nearDuplicatesShareAnLshBucketStrangersDoNot() {
        String edited = TEXT.replace("four engineers", "five engineers");
        long[] a = Lsh.bucketKeys(minHash.signature(MinHash.shingles(TEXT)));
        long[] b = Lsh.bucketKeys(minHash.signature(MinHash.shingles(edited)));
        long[] c = Lsh.bucketKeys(minHash.signature(MinHash.shingles(OTHER_TEXT)));
        assertThat(sharedBands(a, b)).isPositive();
        assertThat(sharedBands(a, c)).isZero();
    }

    @Test
    void phoneFormatsAndShortNumbers() {
        assertThat(DuplicateFinder.normalizePhone("+91-98765 43210")).isEqualTo("9876543210");
        assertThat(DuplicateFinder.normalizePhone("98765-43210")).isEqualTo("9876543210");
        assertThat(DuplicateFinder.normalizePhone("123")).isNull(); // too short to identify anyone
    }

    @Test
    void explanationsNameTheOtherApplicant() {
        var other = applicant("Priya Iyer", "priya@gmail.com", "9876543210", "abc", TEXT, Instant.EPOCH);
        assertThat(DuplicateFinder.describe(Kind.COPIED_CONTENT, 0.87, false, other))
                .isEqualTo("87% of the work history matches the resume of Priya Iyer (priya@gmail.com) — copied or shared.");
    }

    private static int sharedBands(long[] x, long[] y) {
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] == y[i]) {
                n++;
            }
        }
        return n;
    }
}
