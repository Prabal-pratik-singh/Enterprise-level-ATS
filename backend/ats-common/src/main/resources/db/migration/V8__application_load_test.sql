-- true = created by the load test (seed.sh --bulk): the extractor reads it with rules, not the LLM.
-- Every real application (upload page, API, normal seeding) stays false → always the LLM.
ALTER TABLE applications ADD COLUMN load_test BOOLEAN NOT NULL DEFAULT false;
