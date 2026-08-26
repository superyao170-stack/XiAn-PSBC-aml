-- Keep every displayed reasoning score auditable.  These values are weighted
-- evidence scores, not calibrated probabilities.

ALTER TABLE behavior_pattern_occurrence
    ADD COLUMN IF NOT EXISTS confidence_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE risk_event_hypothesis
    ADD COLUMN IF NOT EXISTS confidence_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE alternative_explanation
    ADD COLUMN IF NOT EXISTS confidence_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE technique_occurrence
    ADD COLUMN IF NOT EXISTS confidence_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN behavior_pattern_occurrence.confidence_breakdown IS
    'WEIGHTED_EVIDENCE_V1评分因子、权重和分项结果；不是统计校准概率';
COMMENT ON COLUMN risk_event_hypothesis.confidence_breakdown IS
    'WEIGHTED_EVIDENCE_V1评分因子、权重和分项结果；不是统计校准概率';
COMMENT ON COLUMN alternative_explanation.confidence_breakdown IS
    'WEIGHTED_EVIDENCE_V1评分因子、权重和分项结果；不是统计校准概率';
COMMENT ON COLUMN technique_occurrence.confidence_breakdown IS
    'WEIGHTED_EVIDENCE_V1评分因子、权重和分项结果；不是统计校准概率';
