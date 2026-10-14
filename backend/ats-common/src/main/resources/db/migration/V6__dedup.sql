-- Phase 6 dedup-service: who looks like whom.
-- One MinHash signature (128 slots) per application + the content cluster it belongs to.
CREATE TABLE dedup_signatures (
    application_id UUID PRIMARY KEY REFERENCES applications (id),
    signature      BIGINT[]    NOT NULL,
    cluster_id     UUID        NOT NULL,   -- = own application id when nothing looks alike
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dedup_cluster ON dedup_signatures (cluster_id);

-- LSH: 32 rows per application. "Who else sits in band b, bucket k?" is served by the PK prefix.
CREATE TABLE lsh_buckets (
    band           SMALLINT NOT NULL,
    bucket         BIGINT   NOT NULL,
    application_id UUID     NOT NULL REFERENCES applications (id),
    PRIMARY KEY (band, bucket, application_id)
);

-- Findings, stored from BOTH sides of a pair (A→B and B→A) so each candidate's drawer shows its own.
CREATE TABLE duplicate_matches (
    application_id       UUID        NOT NULL REFERENCES applications (id),
    other_application_id UUID        NOT NULL REFERENCES applications (id),
    kind                 TEXT        NOT NULL
        CHECK (kind IN ('REPEAT_APPLICANT', 'DUPLICATE_FILE', 'COPIED_CONTENT')),
    similarity           NUMERIC     NOT NULL,
    explanation          TEXT        NOT NULL,  -- written by dedup, shown word-for-word
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (application_id, other_application_id)
);
