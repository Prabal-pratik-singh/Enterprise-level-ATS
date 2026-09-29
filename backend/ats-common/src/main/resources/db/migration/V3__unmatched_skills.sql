-- Skills the taxonomy couldn't map to a canonical name. The extractor upserts
-- here so we can see what the ~150-entry dictionary is missing and grow it.
CREATE TABLE unmatched_skills (
    raw_skill     TEXT PRIMARY KEY,                 -- the normalized-but-unknown spelling
    occurrences   INT         NOT NULL DEFAULT 1,   -- how many resumes mentioned it
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
