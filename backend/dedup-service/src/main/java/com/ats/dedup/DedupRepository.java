package com.ats.dedup;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Every SQL the dedup service runs. It READS shared tables and WRITES only its three own. */
@Repository
public class DedupRepository {

    /** The application being checked, plus its job (needed for the outgoing event) and raw profile. */
    public record Subject(DuplicateFinder.Applicant applicant, UUID jobId, String profileJson) {
    }

    // the NEWEST resume version's file fingerprint
    private static final String LATEST_SHA = """
            (select r.sha256 from resumes r where r.application_id = a.id
             order by r.version desc limit 1) as sha256""";

    private final JdbcTemplate jdbc;

    public DedupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Subject> load(UUID applicationId) {
        return jdbc.query("""
                select a.id, a.job_id, a.created_at, c.full_name, c.email, c.phone, p.profile::text as profile,
                """ + LATEST_SHA + """

                from applications a
                join candidates c on c.id = a.candidate_id
                join candidate_profiles p on p.application_id = a.id
                where a.id = ?""",
                (rs, i) -> new Subject(new DuplicateFinder.Applicant(
                        uuid(rs, "id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("sha256"), null, null, null),   // signature + cluster come later
                        uuid(rs, "job_id"), rs.getString("profile")),
                applicationId).stream().findFirst();
    }

    /**
     * Earlier applications worth comparing: same file, same email, same phone, or ANY shared
     * LSH bucket. Only already-signed applications can match — so of any two look-alikes,
     * whichever is processed SECOND finds the first. Order never matters.
     */
    public List<DuplicateFinder.Applicant> lookalikes(UUID me, String sha256, String email, String phone,
                                                     long[] bucketKeys) {
        return jdbc.query("""
                select s.application_id, c.full_name, c.email, c.phone, s.signature, s.cluster_id, a.created_at,
                """ + LATEST_SHA + """

                from dedup_signatures s
                join applications a on a.id = s.application_id
                join candidates c on c.id = a.candidate_id
                where s.application_id <> ?
                  and ( (?::text is not null and exists (select 1 from resumes r
                                                         where r.application_id = a.id and r.sha256 = ?::text))
                     or lower(trim(c.email)) = ?::text
                     or right(regexp_replace(coalesce(c.phone, ''), '\\D', '', 'g'), 10) = ?::text
                     or s.application_id in (
                          select b.application_id from lsh_buckets b
                          join unnest(?::bigint[]) with ordinality as k(bucket, n)
                            on b.band = k.n - 1 and b.bucket = k.bucket) )""",
                applicantMapper(), me, sha256, sha256, email, phone, bucketKeys);
    }

    /** Upsert: a resume v2 replaces the signature but KEEPS the cluster it already belongs to. */
    public void saveSignature(UUID applicationId, long[] signature, UUID clusterId) {
        jdbc.update("""
                insert into dedup_signatures (application_id, signature, cluster_id) values (?, ?, ?)
                on conflict (application_id) do update set signature = excluded.signature, updated_at = now()""",
                applicationId, signature, clusterId);
    }

    /** Replace this application's 32 buckets (a resume v2 must not leave stale ones behind). */
    public void replaceBuckets(UUID applicationId, long[] keys) {
        jdbc.update("delete from lsh_buckets where application_id = ?", applicationId);
        jdbc.batchUpdate("insert into lsh_buckets (band, bucket, application_id) values (?, ?, ?)",
                java.util.stream.IntStream.range(0, keys.length)
                        .mapToObj(band -> new Object[] {(short) band, keys[band], applicationId})
                        .toList());
    }

    /** Replace this application's findings, and write/refresh the mirror row on each other side. */
    public void replaceMatches(DuplicateFinder.Applicant me, List<DuplicateFinder.Finding> findings) {
        jdbc.update("delete from duplicate_matches where application_id = ?", me.applicationId());
        for (DuplicateFinder.Finding f : findings) {
            upsertMatch(me.applicationId(), f.other().applicationId(), f.kind(), f.similarity(),
                    DuplicateFinder.describe(f.kind(), f.similarity(), f.sameFile(), f.other()));
            upsertMatch(f.other().applicationId(), me.applicationId(), f.kind(), f.similarity(),
                    DuplicateFinder.describe(f.kind(), f.similarity(), f.sameFile(), me));   // mirror
        }
    }

    public UUID jobOf(UUID applicationId) {
        return jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
    }

    private void upsertMatch(UUID app, UUID other, DuplicateFinder.Kind kind, double similarity, String text) {
        jdbc.update("""
                insert into duplicate_matches (application_id, other_application_id, kind, similarity, explanation)
                values (?, ?, ?, ?, ?)
                on conflict (application_id, other_application_id)
                do update set kind = excluded.kind, similarity = excluded.similarity,
                              explanation = excluded.explanation""",
                app, other, kind.name(), similarity, text);
    }

    private static RowMapper<DuplicateFinder.Applicant> applicantMapper() {
        return (rs, i) -> new DuplicateFinder.Applicant(
                uuid(rs, "application_id"), rs.getString("full_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("sha256"), longs(rs.getArray("signature")),
                uuid(rs, "cluster_id"), rs.getTimestamp("created_at").toInstant());
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Postgres bigint[] arrives as Long[] — unbox into the long[] MinHash works with. */
    private static long[] longs(Array array) throws SQLException {
        Long[] boxed = (Long[]) array.getArray();
        long[] out = new long[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            out[i] = boxed[i];
        }
        return out;
    }
}
