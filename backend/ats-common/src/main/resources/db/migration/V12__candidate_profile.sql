-- Part 2 C2: a candidate's own profile, built from the resume they upload to their PROFILE (not to a
-- job). One profile per ACCOUNT (users.id): candidate rows are one per application, so one person can
-- have several of those — but only one profile.

CREATE TABLE profile_resumes (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users (id),
    version       INT         NOT NULL,
    s3_key        TEXT        NOT NULL,
    content_type  TEXT,
    size_bytes    BIGINT,
    sha256        TEXT,
    status        TEXT        NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED', 'PARSED', 'EXTRACTED', 'PARSE_FAILED', 'EXTRACT_FAILED')),
    parsed_s3_key TEXT,
    error         TEXT,                          -- why it failed, in plain words (shown on the page)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, version)
);

CREATE TABLE candidate_master_profiles (
    user_id           UUID PRIMARY KEY REFERENCES users (id),
    current_resume_id UUID        REFERENCES profile_resumes (id),
    profile           JSONB,                                     -- what the extractor read (replaced per resume)
    profile_overrides JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- the candidate's own fixes (a re-parse never touches them)
    embedding         vector(768),
    completeness      NUMERIC,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
