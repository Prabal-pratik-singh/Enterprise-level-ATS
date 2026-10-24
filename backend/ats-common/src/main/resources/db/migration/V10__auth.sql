-- Part 2 C1: sign-in. A user is a person who can log in (candidate, recruiter or admin).
-- Candidate rows stay one-per-application as in Part 1; a row is linked to a user only after
-- that user has verified the email address (the account claim).

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          TEXT        NOT NULL UNIQUE CHECK (email = lower(email)),  -- always lower-case, so UNIQUE ignores case
    password_hash  TEXT        NOT NULL,                                      -- BCrypt, never the password itself
    role           TEXT        NOT NULL CHECK (role IN ('CANDIDATE', 'RECRUITER', 'ADMIN')),
    email_verified BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A refresh token is a 7-day ticket for new 15-minute access tokens. Only its SHA-256 is stored,
-- so a copy of this table cannot be used to sign in. Every use cancels it and issues a new one.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ                                   -- NULL = still usable
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);  -- "sign this user out everywhere"

-- Filled only by a verified account claim; Part 1 applicants without an account stay NULL.
ALTER TABLE candidates ADD COLUMN user_id UUID REFERENCES users (id);
CREATE INDEX idx_candidates_user ON candidates (user_id);
