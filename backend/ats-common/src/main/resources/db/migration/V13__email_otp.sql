-- Part 2: e-mail verification by a 6-digit code (OTP) instead of a link. Same table: token_hash now
-- holds HMAC-SHA256(code) keyed with the server secret, expires_at is 10 minutes after sending, and
-- every wrong try is counted — after 5 the code is burned.
ALTER TABLE email_verifications ADD COLUMN attempts INT NOT NULL DEFAULT 0;
