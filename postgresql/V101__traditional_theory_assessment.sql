CREATE TABLE IF NOT EXISTS case_theory_assessment (
    assessment_id VARCHAR(64) PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL,
    workspace_id BIGINT NOT NULL,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    theory_model VARCHAR(64) NOT NULL,
    theory_version VARCHAR(32) NOT NULL,
    stage_code VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    score DECIMAL(8,6) NOT NULL,
    supporting_fact_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    authority_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_snapshot_sha256 CHAR(64),
    producer VARCHAR(128) NOT NULL,
    producer_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_case_theory_assessment_active
    ON case_theory_assessment(case_id, theory_model, theory_version, stage_code)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_case_theory_assessment_case
    ON case_theory_assessment(case_id, status, theory_model, stage_code);

COMMENT ON TABLE case_theory_assessment IS
    '传统理论推理评估，与AMLTRIX技术—战术解释链相互独立';
COMMENT ON COLUMN case_theory_assessment.decision IS
    'CANDIDATE或INSUFFICIENT_EVIDENCE，不表示确定的洗钱阶段事实';
