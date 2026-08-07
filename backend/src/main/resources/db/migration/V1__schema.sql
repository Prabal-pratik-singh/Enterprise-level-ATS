-- ATS pipeline schema (demo-simplified: no auth tables, no field-level
-- encryption columns — see README "honest simplifications").
--
-- Application status state machine:
--   APPLIED -> PARSED -> EXTRACTED -> SCORED
--   (+ FLAGGED_FOR_REVIEW, SHORTLISTED, REJECTED, PARSE_FAILED)
-- Every transition appends a row to application_events.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE candidates (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name  TEXT        NOT NULL,
    email      TEXT        NOT NULL,
    phone      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Not unique: duplicate/shared contacts are a fraud signal to flag, not an insert error
CREATE INDEX idx_candidates_email ON candidates (lower(email));

CREATE TABLE jobs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT        NOT NULL,
    description  TEXT,
    requirements JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE applications (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id       UUID        NOT NULL REFERENCES jobs (id),
    candidate_id UUID        NOT NULL REFERENCES candidates (id),
    status       TEXT        NOT NULL DEFAULT 'APPLIED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, candidate_id)
);
CREATE INDEX idx_applications_job_status ON applications (job_id, status);

CREATE TABLE application_events (
    id             BIGSERIAL PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    event_type     TEXT        NOT NULL,
    details        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_application_events_app ON application_events (application_id);

CREATE TABLE resumes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID        NOT NULL REFERENCES applications (id),
    version        INT         NOT NULL DEFAULT 1,
    s3_key         TEXT        NOT NULL,
    content_type   TEXT,
    size_bytes     BIGINT,
    sha256         TEXT,
    parse_status   TEXT        NOT NULL DEFAULT 'PENDING',
    parse_method   TEXT,
    ocr_confidence NUMERIC,
    parsed_s3_key  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, version)
);
-- Exact-duplicate file detection across candidates (dedup worker)
CREATE INDEX idx_resumes_sha256 ON resumes (sha256);

CREATE TABLE candidate_profiles (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID        NOT NULL UNIQUE REFERENCES applications (id),
    candidate_id   UUID        NOT NULL REFERENCES candidates (id),
    profile        JSONB       NOT NULL,
    embedding      vector(768),
    completeness   NUMERIC,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_profiles_candidate ON candidate_profiles (candidate_id);

CREATE TABLE scores (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID        NOT NULL UNIQUE REFERENCES applications (id),
    final_score    NUMERIC,
    components     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    evidence       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    fraud_score    NUMERIC     NOT NULL DEFAULT 0,
    flags          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_scores_final ON scores (final_score DESC);

-- Idempotency ledger: consumers insert (event_id, consumer) before acting;
-- a second delivery of the same event becomes a no-op.
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- Lightweight transactional outbox: business row + outbox row commit together;
-- a scheduled relay publishes unpublished rows to Kafka. The row id doubles as
-- the event_id in the published envelope.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
