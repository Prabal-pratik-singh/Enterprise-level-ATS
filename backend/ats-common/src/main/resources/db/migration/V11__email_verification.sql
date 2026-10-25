-- Part 2 C1: e-mail verification links. Opening the link proves the user owns the address;
-- only then are older applications with the same e-mail linked to the account (the claim).
-- Like refresh tokens, only the SHA-256 of the link's token is stored.

CREATE TABLE email_verifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,   -- 24 hours after sending
    used_at     TIMESTAMPTZ             -- NULL = not opened yet; a link works once
);
CREATE INDEX idx_email_verifications_user ON email_verifications (user_id);
