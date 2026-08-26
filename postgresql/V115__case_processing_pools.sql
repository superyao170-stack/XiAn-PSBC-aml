-- Human-gated case processing pools.  A case stays in one canonical row and
-- advances by status; JSON artifacts are versioned in the audit table instead
-- of copying a case between physical databases.

CREATE TABLE IF NOT EXISTS case_processing_pool (
    case_id VARCHAR(64) PRIMARY KEY REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    job_id VARCHAR(64) REFERENCES analysis_job(job_id) ON DELETE SET NULL,
    bank_code VARCHAR(32) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    recognition_mode VARCHAR(16) NOT NULL,
    processing_stage VARCHAR(32) NOT NULL,
    source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    suspicious_report JSONB,
    framework_result JSONB,
    graph_snapshot JSONB,
    similarity_result JSONB,
    risk_score NUMERIC(7,4),
    recommended_risk_level VARCHAR(16),
    risk_breakdown JSONB,
    last_error TEXT,
    stage_started_at TIMESTAMPTZ,
    stage_completed_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_case_pool_recognition_mode CHECK (recognition_mode IN ('NEW','HISTORICAL')),
    CONSTRAINT ck_case_pool_stage CHECK (processing_stage IN (
        'PENDING_REPORT','REPORT_PROCESSING','PENDING_EXTRACTION','EXTRACTION_PROCESSING',
        'PENDING_SIMILARITY','SIMILARITY_PROCESSING','PENDING_APPROVAL','APPROVED','FAILED')),
    CONSTRAINT ck_case_pool_recommended_risk CHECK (
        recommended_risk_level IS NULL OR recommended_risk_level IN ('LOW','MEDIUM','HIGH'))
);

CREATE INDEX IF NOT EXISTS idx_case_processing_pool_queue
    ON case_processing_pool(bank_code, processing_stage, scenario_code, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_case_processing_pool_mode
    ON case_processing_pool(bank_code, recognition_mode, created_at DESC);

CREATE TABLE IF NOT EXISTS case_similarity_ranking (
    query_case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    similar_case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    algorithm_rank INTEGER NOT NULL,
    final_rank INTEGER NOT NULL,
    similarity_score NUMERIC(10,8),
    normalized_weight NUMERIC(10,8),
    manually_reordered BOOLEAN NOT NULL DEFAULT false,
    updated_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (query_case_id, similar_case_id),
    CONSTRAINT ck_case_similarity_algorithm_rank CHECK (algorithm_rank > 0),
    CONSTRAINT ck_case_similarity_final_rank CHECK (final_rank > 0),
    CONSTRAINT ck_case_similarity_not_self CHECK (query_case_id <> similar_case_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_case_similarity_final_rank
    ON case_similarity_ranking(query_case_id, final_rank);

CREATE TABLE IF NOT EXISTS case_processing_audit (
    id BIGSERIAL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL REFERENCES cf_risk_case(case_id) ON DELETE CASCADE,
    from_stage VARCHAR(32),
    to_stage VARCHAR(32) NOT NULL,
    action VARCHAR(40) NOT NULL,
    artifact_snapshot JSONB,
    operator_name VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_case_processing_audit_case
    ON case_processing_audit(case_id, created_at DESC);

-- Each failed upload attempt is retained even after a retry clears the current
-- job error. This powers the failure-reason/detail panel and provides an audit
-- trail for repeated retries.
CREATE TABLE IF NOT EXISTS upload_job_failure_attempt (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    failed_step_order INTEGER,
    failed_step_name VARCHAR(128),
    error_message TEXT NOT NULL,
    failure_details JSONB NOT NULL DEFAULT '{}'::jsonb,
    failed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retried_at TIMESTAMPTZ,
    retried_by VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_upload_job_failure_attempt_job
    ON upload_job_failure_attempt(job_id, failed_at DESC);

-- V4 constrained cf_risk_case.case_status to the legacy review workflow.
-- Expand it before backfilling any existing cases into the new four-stage flow.
ALTER TABLE cf_risk_case DROP CONSTRAINT IF EXISTS ck_cf_risk_case_status;
ALTER TABLE cf_risk_case
    ADD CONSTRAINT ck_cf_risk_case_status CHECK (case_status IN (
        'DRAFT','IN_REVIEW','PENDING_REPORT','PENDING_EXTRACTION','PENDING_SIMILARITY',
        'PENDING_APPROVAL','APPROVED','REJECTED','CLOSED','REOPENED','FAILED'));

INSERT INTO sys_dict_data(dict_type,dict_value,dict_label,sort_order)
SELECT 'case_status',value,label,sort_order
  FROM (VALUES
    ('PENDING_REPORT','待生成报告',10),
    ('PENDING_EXTRACTION','待框架抽取',20),
    ('PENDING_SIMILARITY','待相似匹配',30),
    ('PENDING_APPROVAL','待复核审批',40),
    ('APPROVED','已审核通过',50)
  ) AS status(value,label,sort_order)
 WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data existing
     WHERE existing.dict_type='case_status' AND existing.dict_value=status.value);

-- Normalize existing structured cases into the new business pools.  Historical
-- cases with an extracted library document can go straight to approval; other
-- cases wait for report generation.
INSERT INTO case_processing_pool
    (case_id,job_id,bank_code,scenario_code,recognition_mode,processing_stage,
     source_payload,suspicious_report,framework_result,graph_snapshot)
SELECT c.case_id,
       (SELECT rel.job_id FROM case_analysis_job_rel rel WHERE rel.case_id=c.case_id ORDER BY rel.created_at DESC LIMIT 1),
       c.bank_code,c.scenario_code,COALESCE(l.recognition_mode,'NEW'),
       CASE WHEN l.recognition_mode='HISTORICAL' AND l.case_document IS NOT NULL
            THEN 'PENDING_APPROVAL' ELSE 'PENDING_REPORT' END,
       '{}'::jsonb,
       CASE WHEN l.case_document ? 'analysis_texts'
            THEN jsonb_build_object('analysisTexts',l.case_document->'analysis_texts') END,
       l.case_document,
       gs.metadata
  FROM cf_risk_case c
  LEFT JOIN structured_case_library l ON l.case_id=c.case_id AND l.status='ACTIVE'
  LEFT JOIN graph_snapshot gs ON gs.snapshot_id=c.graph_snapshot_id
 WHERE c.deleted=false AND c.case_type='STRUCTURED_CASE'
ON CONFLICT (case_id) DO NOTHING;

UPDATE cf_risk_case c
   SET case_status=p.processing_stage,
       risk_level=CASE WHEN p.processing_stage='APPROVED' THEN c.risk_level ELSE NULL END,
       updated_at=CURRENT_TIMESTAMP
  FROM case_processing_pool p
 WHERE p.case_id=c.case_id AND c.deleted=false;

-- Replace the old review/approval pair with one four-page processing group.
UPDATE sys_menu SET visible=false,updated_at=CURRENT_TIMESTAMP
 WHERE path IN ('/case/review','/case/approval');

UPDATE sys_menu SET menu_name='案例上传',path='/analysis/upload',
       component='views/analysis/PipelineWorkbench.vue',sort_order=2,visible=true,
       updated_at=CURRENT_TIMESTAMP
 WHERE path='/analysis';

UPDATE sys_menu SET visible=false,updated_at=CURRENT_TIMESTAMP
 WHERE path IN ('/analysis/aml','/analysis/fraud','/analysis/structured');

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT p.id,'新增案例处理','/case/processing',NULL,'el-icon-connection',2,'DIRECTORY','case:processing:view',true
  FROM sys_menu p WHERE p.path='/case'
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/case/processing');

INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT p.id,v.name,v.path,v.component,'el-icon-document',v.sort_order,'MENU','case:processing:view',true
  FROM sys_menu p
 CROSS JOIN (VALUES
   ('可疑报告','/case/processing/report','views/case/CaseProcessing.vue',1),
   ('框架抽取','/case/processing/framework','views/case/CaseProcessing.vue',2),
   ('相似案例','/case/processing/similarity','views/case/CaseProcessing.vue',3),
   ('复核审批','/case/processing/approval','views/case/CaseProcessing.vue',4)
 ) AS v(name,path,component,sort_order)
 WHERE p.path='/case/processing'
   AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path=v.path);

COMMENT ON TABLE case_processing_pool IS 'Canonical business pools for uploaded historical and new cases';
COMMENT ON COLUMN case_processing_pool.processing_stage IS 'Single source of truth for the four human-gated processing queues';
COMMENT ON TABLE case_similarity_ranking IS 'Persisted algorithm and human-final similarity ordering used by panorama graphs';
COMMENT ON TABLE upload_job_failure_attempt IS 'Immutable failure details for case-upload job attempts and retries';
