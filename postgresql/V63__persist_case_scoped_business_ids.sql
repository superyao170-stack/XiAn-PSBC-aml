ALTER TABLE cf_risk_event
    ADD COLUMN IF NOT EXISTS business_id VARCHAR(32);

ALTER TABLE risk_signal
    ADD COLUMN IF NOT EXISTS business_id VARCHAR(32);

ALTER TABLE case_signal_rel
    ADD COLUMN IF NOT EXISTS business_id VARCHAR(32);

ALTER TABLE case_core_chain_node
    ADD COLUMN IF NOT EXISTS business_id VARCHAR(32);

ALTER TABLE case_core_chain_edge
    ADD COLUMN IF NOT EXISTS business_id VARCHAR(32);

WITH ranked AS (
    SELECT e.event_id,c.id AS case_seq,
           row_number() OVER (PARTITION BY e.case_id ORDER BY e.event_id) AS seq
    FROM cf_risk_event e
    JOIN cf_risk_case c ON c.case_id=e.case_id
)
UPDATE cf_risk_event e
SET business_id = ranked.case_seq || '-EVT-' ||
                  lpad(ranked.seq::text,GREATEST(3,length(ranked.seq::text)),'0')
FROM ranked
WHERE ranked.event_id=e.event_id
  AND (e.business_id IS NULL OR btrim(e.business_id)='');

WITH ranked AS (
    SELECT r.id,r.signal_id,c.id AS case_seq,
           row_number() OVER (PARTITION BY r.case_id ORDER BY r.signal_id) AS seq
    FROM case_signal_rel r
    JOIN cf_risk_case c ON c.case_id=r.case_id
)
UPDATE case_signal_rel r
SET business_id = ranked.case_seq || '-EVD-' ||
                  lpad(ranked.seq::text,GREATEST(3,length(ranked.seq::text)),'0')
FROM ranked
WHERE ranked.id=r.id
  AND (r.business_id IS NULL OR btrim(r.business_id)='');

UPDATE risk_signal s
SET business_id=r.business_id
FROM case_signal_rel r
WHERE r.signal_id=s.signal_id
  AND (s.business_id IS NULL OR btrim(s.business_id)='');

WITH ranked AS (
    SELECT n.chain_id,n.node_key,c.id AS case_seq,n.canonical_type,
           row_number() OVER (
               PARTITION BY n.chain_id,n.canonical_type ORDER BY n.node_key
           ) AS seq
    FROM case_core_chain_node n
    JOIN cf_risk_case c ON c.case_id=n.case_id
), assigned AS (
    SELECT chain_id,node_key,
           CASE canonical_type
             WHEN 'CASE' THEN case_seq::text
             WHEN 'CUSTOMER' THEN case_seq || '-CUS-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'ORGANIZATION' THEN case_seq || '-ORG-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'MERCHANT' THEN case_seq || '-MCH-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'ACCOUNT' THEN case_seq || '-ACC-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'WALLET' THEN case_seq || '-WAL-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'DEVICE' THEN case_seq || '-DEV-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'IP_ADDRESS' THEN case_seq || '-IPA-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'IPADDRESS' THEN case_seq || '-IPA-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'ADDRESS' THEN case_seq || '-ADR-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'EVIDENCE' THEN case_seq || '-EVD-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'EVENT' THEN case_seq || '-EVT-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'INDICATOR_RESULT' THEN case_seq || '-IND-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'BEHAVIOR_PATTERN' THEN case_seq || '-PAT-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'RISK_HYPOTHESIS' THEN case_seq || '-RSK-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             WHEN 'INVESTIGATION_HYPOTHESIS' THEN case_seq || '-INV-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
             ELSE case_seq || '-OBJ-' || lpad(seq::text,GREATEST(3,length(seq::text)),'0')
           END AS business_id
    FROM ranked
)
UPDATE case_core_chain_node n
SET business_id=a.business_id,
    properties=jsonb_set(n.properties,'{businessId}',to_jsonb(a.business_id),true)
FROM assigned a
WHERE a.chain_id=n.chain_id AND a.node_key=n.node_key;

WITH ranked AS (
    SELECT e.chain_id,e.edge_id,c.id AS case_seq,
           row_number() OVER (PARTITION BY e.chain_id
                              ORDER BY e.source_id,e.relation_type,e.target_id,e.edge_id) AS seq
    FROM case_core_chain_edge e
    JOIN case_core_chain_snapshot s ON s.chain_id=e.chain_id
    JOIN cf_risk_case c ON c.case_id=s.case_id
)
UPDATE case_core_chain_edge e
SET business_id=ranked.case_seq || '-REL-' ||
                lpad(ranked.seq::text,GREATEST(3,length(ranked.seq::text)),'0')
FROM ranked
WHERE ranked.chain_id=e.chain_id AND ranked.edge_id=e.edge_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_case_event_business_id
    ON cf_risk_event(case_id,business_id) WHERE business_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_case_signal_business_id
    ON case_signal_rel(case_id,business_id) WHERE business_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_core_chain_node_business_id
    ON case_core_chain_node(chain_id,business_id) WHERE business_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_core_chain_edge_business_id
    ON case_core_chain_edge(chain_id,business_id) WHERE business_id IS NOT NULL;

CREATE OR REPLACE FUNCTION assign_case_event_business_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    case_seq BIGINT;
    next_seq INTEGER;
BEGIN
    IF NEW.business_id IS NOT NULL AND btrim(NEW.business_id)<>'' THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('event-business-id:' || NEW.case_id));
    SELECT id INTO case_seq FROM cf_risk_case WHERE case_id=NEW.case_id;
    SELECT COALESCE(MAX(substring(business_id FROM '([0-9]+)$')::INTEGER),0)+1
      INTO next_seq
      FROM cf_risk_event
     WHERE case_id=NEW.case_id AND business_id ~ '-EVT-[0-9]+$';
    NEW.business_id := case_seq || '-EVT-' ||
                       lpad(next_seq::text,GREATEST(3,length(next_seq::text)),'0');
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_assign_case_event_business_id ON cf_risk_event;
CREATE TRIGGER trg_assign_case_event_business_id
BEFORE INSERT OR UPDATE OF case_id ON cf_risk_event
FOR EACH ROW EXECUTE FUNCTION assign_case_event_business_id();

CREATE OR REPLACE FUNCTION assign_case_signal_business_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    case_seq BIGINT;
    next_seq INTEGER;
BEGIN
    IF NEW.business_id IS NULL OR btrim(NEW.business_id)='' THEN
        PERFORM pg_advisory_xact_lock(hashtext('evidence-business-id:' || NEW.case_id));
        SELECT id INTO case_seq FROM cf_risk_case WHERE case_id=NEW.case_id;
        SELECT COALESCE(MAX(substring(business_id FROM '([0-9]+)$')::INTEGER),0)+1
          INTO next_seq
          FROM case_signal_rel
         WHERE case_id=NEW.case_id AND business_id ~ '-EVD-[0-9]+$';
        NEW.business_id := case_seq || '-EVD-' ||
                           lpad(next_seq::text,GREATEST(3,length(next_seq::text)),'0');
    END IF;
    UPDATE risk_signal
       SET business_id=COALESCE(NULLIF(btrim(business_id),''),NEW.business_id)
     WHERE signal_id=NEW.signal_id;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_assign_case_signal_business_id ON case_signal_rel;
CREATE TRIGGER trg_assign_case_signal_business_id
BEFORE INSERT OR UPDATE OF case_id,signal_id ON case_signal_rel
FOR EACH ROW EXECUTE FUNCTION assign_case_signal_business_id();

ALTER TABLE graph_relation_type_registry
    ADD COLUMN IF NOT EXISTS framework_category_code VARCHAR(32);

INSERT INTO case_framework_option_metadata
    (field_code,field_name,option_code,option_label,option_value,sort_order,description)
VALUES
    ('relationType','关系类型','SEQUENTIAL','顺承关系','顺承关系',10,'事件一发生在事件二之前'),
    ('relationType','关系类型','INVOLVES','涉及关系','涉及关系',20,'案例涉及客户或账号'),
    ('relationType','关系类型','CONTAINS','包含关系','包含关系',30,'案例包含事件'),
    ('relationType','关系类型','PARTICIPATES','参与关系','参与关系',40,'客户或账户参与事件'),
    ('relationType','关系类型','HOLDS','持有关系','持有关系',50,'客户持有账户'),
    ('relationType','关系类型','SOCIAL','社会关系','社会关系',60,'客户与客户之间的社会关系'),
    ('relationType','关系类型','SOURCE','来源关系','来源关系',70,'事件、客户、账户或关系来源于证据或数据'),
    ('relationType','关系类型','RESPONSE','应对关系','应对关系',80,'处置措施用于应对案例或事件'),
    ('relationType','关系类型','HIERARCHICAL','上下位关系','上下位关系',90,'事件父子层级关系，适用于事理图谱'),
    ('relationType','关系类型','CAUSAL','因果关系','因果关系',100,'事理图谱中的因果及其他推理关系')
ON CONFLICT (field_code,option_code) DO UPDATE SET
    field_name=EXCLUDED.field_name,
    option_label=EXCLUDED.option_label,
    option_value=EXCLUDED.option_value,
    sort_order=EXCLUDED.sort_order,
    description=EXCLUDED.description,
    status='ACTIVE',
    updated_at=CURRENT_TIMESTAMP;

UPDATE graph_relation_type_registry
SET framework_category_code=CASE
    WHEN relation_type IN ('PRECEDES','FOLLOWS','SAME_SESSION_NEXT') THEN 'SEQUENTIAL'
    WHEN relation_type IN ('INVOLVES_SUBJECT','INVOLVES_ASSET','INVOLVES_ENVIRONMENT',
                           'SUBJECT_ACCOUNT','INVOLVES_ADDRESS') THEN 'INVOLVES'
    WHEN relation_type IN ('CONTAINS_EVENT','INVESTIGATION_SCOPE','HAS_EVIDENCE') THEN 'CONTAINS'
    WHEN relation_type IN ('ACTOR','SUBJECT','BENEFICIARY','SOURCE_ACCOUNT','TARGET_ACCOUNT',
                           'SOURCE_WALLET','TARGET_WALLET','USES_DEVICE','FROM_IP','AT_MERCHANT') THEN 'PARTICIPATES'
    WHEN relation_type IN ('OWNS_ACCOUNT','CONTROLS','OPERATES','HAS_ADDRESS') THEN 'HOLDS'
    WHEN relation_type IN ('ASSOCIATED_WITH','OVERLAPS') THEN 'SOCIAL'
    WHEN relation_type IN ('EVIDENCE_SUPPORTS','EVIDENCE_CONTRADICTS','DERIVED_FROM_SOURCE') THEN 'SOURCE'
    WHEN relation_type IN ('TARGETS','RAISES_INVESTIGATION_HYPOTHESIS') THEN 'RESPONSE'
    WHEN relation_type IN ('INPUT_TO_INDICATOR_RESULT','MATCHES_BEHAVIOR_PATTERN',
                           'SUPPORTS_BEHAVIOR_PATTERN','SUPPORTS_RISK_HYPOTHESIS') THEN 'CAUSAL'
    ELSE COALESCE(framework_category_code,'CAUSAL')
END
WHERE schema_version='1.8';
