-- Phase 8 LLM rerank: a bounded nudge on the match score, with the model's reasons.
ALTER TABLE scores ADD COLUMN rerank_adjustment numeric NOT NULL DEFAULT 0
    CHECK (rerank_adjustment BETWEEN -0.10 AND 0.10);    -- ±10%, enforced by the database too
ALTER TABLE scores ADD COLUMN llm_summary jsonb;          -- {summary, must_have_coverage, depth, red_flags, model, at}

-- final = match × (1 + adjustment) − fraud penalty, never below 0 (V5's rule plus the nudge)
DROP INDEX idx_scores_final;
ALTER TABLE scores DROP COLUMN final_score;
ALTER TABLE scores ADD COLUMN final_score numeric GENERATED ALWAYS AS (
    CASE WHEN match_score IS NULL THEN NULL
         ELSE greatest(round(match_score * (1 + rerank_adjustment), 2) - fraud_penalty, 0) END
) STORED;
CREATE INDEX idx_scores_final ON scores (final_score DESC);
