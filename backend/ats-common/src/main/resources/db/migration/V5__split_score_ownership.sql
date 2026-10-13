-- V5: split the score into the parts each service owns.
-- matcher-service writes match_score; fraud-service (Phase 6) writes fraud_penalty.
-- final_score is no longer written by anyone: Postgres derives it on every write,
-- so the two services can finish in either order and the number is always right.

ALTER TABLE scores ADD COLUMN match_score numeric;                          -- 0..100 from the matcher
ALTER TABLE scores ADD COLUMN fraud_penalty numeric NOT NULL DEFAULT 0;     -- points subtracted by the Trust Service

-- Today's final scores ARE pure match scores (no penalties exist yet) — keep them.
UPDATE scores SET match_score = final_score;

-- A plain column can't be turned into a generated one in place: drop and re-add.
DROP INDEX idx_scores_final;
ALTER TABLE scores DROP COLUMN final_score;
ALTER TABLE scores ADD COLUMN final_score numeric GENERATED ALWAYS AS (
    -- no match yet (Trust Service finished first) -> still "unscored", not 0.
    -- greatest(NULL, 0) would return 0, so the NULL case must be explicit.
    CASE WHEN match_score IS NULL THEN NULL
         ELSE greatest(match_score - fraud_penalty, 0) END                 -- never below 0
) STORED;                                                                    -- STORED = saved on disk, so it can be indexed
CREATE INDEX idx_scores_final ON scores (final_score DESC);                  -- the ranked list sorts by this
