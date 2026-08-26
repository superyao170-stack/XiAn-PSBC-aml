package com.datagraph.bank.service;

import com.datagraph.bank.util.EvidenceTitleNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CaseCoreChainService {
    private static final String SCHEMA_VERSION = "1.8";
    private static final String SNAPSHOT_FORMAT_VERSION = "1.8.1";
    private static final Set<String> GRAPH_NODE_TYPES = Set.of(
            "CASE", "CUSTOMER", "ORGANIZATION", "MERCHANT", "ACCOUNT", "WALLET",
            "DEVICE", "IP_ADDRESS", "ADDRESS", "EVIDENCE", "EVENT",
            "BEHAVIOR_PATTERN", "RISK_HYPOTHESIS", "INVESTIGATION_HYPOTHESIS");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CoreChainGraphValidator graphValidator = new CoreChainGraphValidator();

    public CaseCoreChainService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String caseBankCode(String caseId) {
        return jdbc.queryForObject(
                "SELECT bank_code FROM cf_risk_case WHERE case_id=? AND deleted=false",
                String.class, caseId);
    }

    public List<Map<String, Object>> snapshots(String caseId) {
        return normalize(jdbc.queryForList("""
            SELECT chain_id AS "chainId",revision,maturity,status,schema_version AS "schemaVersion",
                   node_counts AS "nodeCounts",edge_count AS "edgeCount",
                   validation_metrics AS "validationMetrics",
                   projection_status AS "projectionStatus",inference_time AS "inferenceTime",
                   content_sha256 AS "contentSha256"
            FROM case_core_chain_snapshot
            WHERE case_id=? ORDER BY revision DESC
            """, caseId), "nodeCounts", "validationMetrics");
    }

    public Map<String, Object> snapshotDetail(String caseId, String chainId) {
        Map<String, Object> snapshot = normalize(jdbc.queryForList("""
            SELECT chain_id AS "chainId",case_id AS "caseId",revision,maturity,status,
                   schema_version AS "schemaVersion",input_snapshot_sha256 AS "inputSnapshotSha256",
                   knowledge_release_ref AS "knowledgeReleaseRef",model_ref AS "modelRef",
                   node_counts AS "nodeCounts",edge_count AS "edgeCount",
                   primary_path AS "primaryPath",validation_issues AS "validationIssues",
                   validation_metrics AS "validationMetrics",projection_status AS "projectionStatus",
                   content_sha256 AS "contentSha256",inference_time AS "inferenceTime"
            FROM case_core_chain_snapshot WHERE case_id=? AND chain_id=?
            """, caseId, chainId), "nodeCounts", "primaryPath", "validationIssues",
                "validationMetrics").stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Core-chain snapshot not found"));
        snapshot.put("domainTypeSummary", props(
                "eventGraphNodeTypes", 11, "reasoningGraphNodeTypes", 3,
                "narrativeObjectTypes", 1, "knowledgeDefinitionTypes", 5,
                "auxiliaryRecordTypes", 4));
        snapshot.put("runtimeObjectCounts", frozenObjectCounts(chainId));
        snapshot.put("knowledgeRefs", frozenKnowledgeRefs(chainId));
        return snapshot;
    }

    public Map<String, Object> snapshotGraph(String caseId, String chainId) {
        snapshotDetail(caseId, chainId);
        List<Map<String, Object>> nodes = normalize(jdbc.queryForList("""
            SELECT node_key AS id,node_key AS uid,legacy_type AS label,display_name AS name,
                   business_id AS "businessId",properties
            FROM case_core_chain_node WHERE chain_id=? ORDER BY node_key
            """, chainId), "properties");
        List<Map<String, Object>> edges = normalize(jdbc.queryForList("""
            SELECT e.edge_id AS id,e.edge_id AS uid,e.source_id AS source,e.target_id AS target,
                   e.relation_type AS type,e.business_id AS "businessId",
                   jsonb_build_object('businessId',e.business_id,
                     'sourceBusinessId',sn.business_id,'targetBusinessId',tn.business_id,
                     'sourceType',e.source_type,'targetType',e.target_type,
                     'edgeCategory',e.edge_category,'pathRole',e.path_role,
                     'edgeOrigin',e.edge_origin,'evidenceRefs',e.evidence_refs,
                     'ruleRef',e.rule_ref,'confidence',e.confidence,
                     'relationName',COALESCE(r.display_name,e.relation_type),
                     'relationCategory',m.option_label,
                     'relationDomain',r.graph_domain,
                     'semanticLevel',r.semantic_level,
                     'directionSemantics',r.direction_semantics) AS properties
            FROM case_core_chain_edge e
            LEFT JOIN graph_relation_type_registry r
              ON r.relation_type=e.relation_type
             AND r.schema_version='1.8' AND r.status='ACTIVE'
            LEFT JOIN case_framework_option_metadata m
              ON m.field_code='relationType'
             AND m.option_code=r.framework_category_code AND m.status='ACTIVE'
            LEFT JOIN case_core_chain_node sn
              ON sn.chain_id=e.chain_id AND sn.node_key=e.source_id
            LEFT JOIN case_core_chain_node tn
              ON tn.chain_id=e.chain_id AND tn.node_key=e.target_id
            WHERE e.chain_id=? ORDER BY e.edge_id
            """, chainId), "properties");
        return props("caseId", caseId, "chainId", chainId, "nodes", nodes, "edges", edges,
                "knowledgeRefs", frozenKnowledgeRefs(chainId));
    }

    public List<Map<String, Object>> snapshotClaims(String caseId, String chainId) {
        snapshotDetail(caseId, chainId);
        return normalize(jdbc.queryForList("""
            SELECT c.claim_id AS "claimId",c.matter_id AS "matterId",
                   c.claim_order AS "claimOrder",c.claim_type AS "claimType",
                   c.claim_text AS "claimText",c.certainty,
                   c.supporting_refs AS "supportingRefs",
                   c.contradicting_refs AS "contradictingRefs",
                   c.content_sha256 AS "contentSha256"
            FROM case_core_chain_narrative_ref n
            JOIN case_matter_claim c ON c.matter_id=n.matter_id
            WHERE n.chain_id=? ORDER BY c.matter_id,c.claim_order
                """, chainId), "supportingRefs", "contradictingRefs");
    }

    public Map<String, Object> snapshotEvidence(String caseId, String chainId) {
        snapshotDetail(caseId, chainId);
        List<Map<String, Object>> evidenceNodes = normalize(jdbc.queryForList("""
            SELECT node_key AS id,display_name AS name,properties
            FROM case_core_chain_node
            WHERE chain_id=? AND canonical_type='EVIDENCE' ORDER BY node_key
            """, chainId), "properties");
        List<Map<String, Object>> edgeEvidence = normalize(jdbc.queryForList("""
            SELECT edge_id AS "edgeId",source_id AS source,relation_type AS relation,
                   target_id AS target,evidence_refs AS "evidenceRefs",
                   edge_origin AS "edgeOrigin"
            FROM case_core_chain_edge
            WHERE chain_id=? AND evidence_refs<>'[]'::jsonb ORDER BY edge_id
            """, chainId), "evidenceRefs");
        return props("caseId", caseId, "chainId", chainId,
                "evidenceNodes", evidenceNodes, "edgeEvidence", edgeEvidence);
    }

    public Map<String, Object> snapshotValidation(String caseId, String chainId) {
        Map<String, Object> detail = snapshotDetail(caseId, chainId);
        return props("chainId", chainId, "status", detail.get("status"),
                "issues", detail.get("validationIssues"),
                "metrics", detail.get("validationMetrics"));
    }

    @Transactional
    public Map<String, Object> reproject(String caseId, String chainId) {
        snapshotDetail(caseId, chainId);
        jdbc.update("UPDATE case_core_chain_snapshot SET projection_status='PENDING' WHERE chain_id=?",
                chainId);
        jdbc.update("""
            INSERT INTO case_core_chain_outbox
              (event_id,bank_code,workspace_id,case_id,chain_id,event_type,payload,status)
            SELECT ?,bank_code,workspace_id,case_id,chain_id,'CORE_CHAIN_PROJECT',
                   jsonb_build_object('chainId',chain_id,'manual',true),'PENDING'
            FROM case_core_chain_snapshot WHERE chain_id=?
            ON CONFLICT (event_id) DO UPDATE SET status='PENDING',retry_count=0,
                 next_retry_at=NULL,last_error=NULL,projected_at=NULL
            """, "OUTBOX-REPROJECT-" + chainId, chainId);
        return props("caseId", caseId, "chainId", chainId, "projectionStatus", "PENDING");
    }

    public Map<String, Object> graph(String caseId) {
        Map<String, Object> context = jdbc.queryForMap("""
            SELECT id AS case_sequence_id,case_id,case_name,bank_code,workspace_id,case_source
            FROM cf_risk_case WHERE case_id=? AND deleted=false
            """, caseId);
        List<Map<String, Object>> patterns = normalize(jdbc.queryForList("""
            SELECT o.occurrence_id,o.pattern_code,o.pattern_version,o.event_refs,
                   o.indicator_result_refs,o.pattern_confidence,o.evidence_strength,
                   o.node_maturity,o.status,d.pattern_name
            FROM behavior_pattern_occurrence o
            LEFT JOIN event_pattern_definition d ON d.pattern_code=o.pattern_code
            WHERE o.case_id=? AND o.status='ACTIVE' ORDER BY o.occurrence_id
            """, caseId), "event_refs", "indicator_result_refs");
        List<Map<String, Object>> risks = normalize(jdbc.queryForList("""
            SELECT risk_event_id,risk_event_type,title,summary,behavior_occurrence_refs,event_refs,
                   risk_confidence,evidence_strength,risk_event_type_version,
                   risk_event_definition_ref,definition_binding_method,
                   definition_binding_confidence,node_maturity,review_status,status
            FROM risk_event_hypothesis
            WHERE case_id=? AND status='ACTIVE' ORDER BY risk_event_id
            """, caseId), "behavior_occurrence_refs", "event_refs");
        List<Map<String, Object>> techniques = normalize(jdbc.queryForList("""
            SELECT occurrence_id,technique_code,technique_version,explanation,
                   behavior_occurrence_refs,indicator_result_refs,risk_event_refs,
                   mapping_confidence,risk_confidence,evidence_strength,node_maturity,
                   review_status,status
            FROM technique_occurrence
            WHERE case_id=? AND status<>'SUPERSEDED' ORDER BY occurrence_id
            """, caseId), "behavior_occurrence_refs", "indicator_result_refs", "risk_event_refs");
        List<Map<String, Object>> matters = normalize(jdbc.queryForList("""
            SELECT matter_id,matter_type,summary,certainty,event_refs,indicator_result_refs,
                   technique_occurrence_refs,behavior_occurrence_refs,risk_event_refs,status
            FROM case_matter_explanation
            WHERE case_id=? AND status='ACTIVE' ORDER BY matter_id
            """, caseId), "event_refs", "indicator_result_refs", "technique_occurrence_refs",
                "behavior_occurrence_refs", "risk_event_refs");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        JsonNode workerOutput = latestWorkerOutput(caseId);

        String bankCode = Objects.toString(context.get("bank_code"));
        String workspaceId = Objects.toString(context.get("workspace_id"), "1");
        addNode(nodes, nodeIds, caseId, "CASE", "CASE", "CASE",
                Objects.toString(context.get("case_name"), caseId),
                props("canonicalType", "CASE", "epistemicType", "CONTEXT",
                        "caseId", caseId, "bankCode", bankCode, "workspaceId", workspaceId));
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT event_id,event_name,event_type,event_time,confidence,event_frame_code,
                   event_frame_version,definition_binding_status,canonical_event_id,
                   identity_resolution_status,event_quality_score,
                   semantic_profile_code,semantic_profile_version,lifecycle_code,
                   lifecycle_version,lifecycle_state,fact_level,evidence_refs,business_id
            FROM cf_risk_event WHERE case_id=? AND deleted=false ORDER BY event_time,event_id
            """, caseId);
        for (Map<String, Object> event : events) {
            String eventId = text(event, "event_id");
            addNode(nodes, nodeIds, eventId, "EVENT", "EVENT", "FACT_EVENT",
                    first(event, "event_name", "event_type", "event_id"),
                    withCommon(event, "EVENT", "OBSERVED"));
            addEdge(edges, caseId, "CASE", "INVESTIGATION_SCOPE", eventId, "EVENT",
                    "FACT", "SCOPE", "CASE_ANALYSIS_SCOPE", false);
        }
        boolean hasWorkerEvidence = appendWorkerFacts(
                caseId, workerOutput, events, nodes, nodeIds, edges);
        appendEvidence(caseId, context, events, nodes, nodeIds, edges, hasWorkerEvidence);
        appendEventRelations(events, edges);

        for (Map<String, Object> pattern : patterns) {
            String occurrenceId = text(pattern, "occurrence_id");
            String graphId = scoped(caseId, occurrenceId);
            String code = text(pattern, "pattern_code");
            String version = text(pattern, "pattern_version");
            List<String> patternEventRefs = refs(pattern.get("event_refs"));
            Map<String, Object> patternProperties =
                    withCommon(pattern, "BEHAVIOR_PATTERN", "DETECTED");
            patternProperties.put("instanceQuestion", "这些已发生事件共同呈现了什么组合行为？");
            patternProperties.put("businessMeaning",
                    patternBehaviorMeaning(patternEventRefs, events));
            patternProperties.put("supportSummary",
                    reasoningEventSummary(patternEventRefs, events));
            patternProperties.put("decisionBoundary",
                    "候选行为模式；仍需结合交易流水、主体关系和指标结果复核。");
            addNode(nodes, nodeIds, graphId, "BEHAVIORPATTERN",
                    "BEHAVIOR_PATTERN", "REASONING_GRAPH",
                    patternInstanceName(pattern, patternEventRefs, events),
                    patternProperties);
            for (String eventId : refs(pattern.get("event_refs"))) {
                addEdge(edges, eventId, "EVENT", "MATCHES_BEHAVIOR_PATTERN",
                        graphId, "BEHAVIOR_PATTERN", "INFERENCE", "PRIMARY",
                        "RULE", false);
            }
        }

        Map<String, Map<String, Object>> riskById = new LinkedHashMap<>();
        for (Map<String, Object> risk : risks) {
            String riskId = text(risk, "risk_event_id");
            riskById.put(riskId, risk);
            String graphId = scoped(caseId, riskId);
            String type = text(risk, "risk_event_type");
            String version = text(risk, "risk_event_type_version");
            List<String> riskEventRefs = refs(risk.get("event_refs"));
            List<String> behaviorRefs = refs(risk.get("behavior_occurrence_refs"));
            Map<String, Object> riskProperties =
                    withCommon(risk, "RISK_HYPOTHESIS", "INFERRED");
            riskProperties.put("instanceQuestion", "上述行为组合可能意味着什么风险？");
            riskProperties.put("businessMeaning",
                    "该实例认为上述 " + behaviorRefs.size() + " 个行为模式及 "
                            + riskEventRefs.size()
                            + " 个事件可能构成复合洗钱路径；这是待复核的风险解释，"
                            + "不是已确认违法事实。");
            riskProperties.put("supportSummary",
                    "模式 " + behaviorRefs.size() + " 个、事件 " + riskEventRefs.size()
                            + " 个；风险置信度 " + percent(risk.get("risk_confidence"))
                            + "，证据强度 " + first(risk, "evidence_strength") + "。");
            riskProperties.put("decisionBoundary",
                    "当前复核状态：" + first(risk, "review_status", "status")
                            + "；外部核验完成前不得作为最终定性。");
            addNode(nodes, nodeIds, graphId, "RISKHYPOTHESIS", "RISK_HYPOTHESIS",
                    "REASONING_GRAPH", riskInstanceName(risk), riskProperties);
            for (String patternId : refs(risk.get("behavior_occurrence_refs"))) {
                addEdge(edges, scoped(caseId, patternId), "BEHAVIOR_PATTERN",
                        "SUPPORTS_RISK_HYPOTHESIS", graphId, "RISK_HYPOTHESIS",
                        "INFERENCE", "PRIMARY", "MODEL", false);
            }
            if (version.isBlank()) {
                issues.add(issue("CC_DEF_001", "ERROR", graphId,
                        "风险事件未绑定有效的 RiskEventDefinition，分支不得进入正式技术映射。"));
            }
        }

        // V1.8 boundary: knowledge definitions, facets, binding records and
        // NarrativeProjection objects are relational/API products, never TuGraph nodes.
        Set<String> rejectedBoundaryIds = new LinkedHashSet<>();
        nodes.removeIf(node -> {
            String canonical = canonicalType(node);
            boolean rejected = !GRAPH_NODE_TYPES.contains(canonical);
            if (rejected) rejectedBoundaryIds.add(Objects.toString(node.get("id"), ""));
            return rejected;
        });
        edges.removeIf(edge -> rejectedBoundaryIds.contains(Objects.toString(edge.get("source"), ""))
                || rejectedBoundaryIds.contains(Objects.toString(edge.get("target"), "")));
        appendInvestigationHypotheses(caseId, nodes, nodeIds, edges);
        completeEvidenceMeaning(caseId, events, risks, nodes, edges);
        applyRelationContracts(nodes, edges, issues);
        stampInstanceContract(caseId, nodes);
        assignBusinessIds(((Number) context.get("case_sequence_id")).longValue(), nodes, edges);
        CoreChainGraphValidator.ValidationResult graphValidation =
                graphValidator.close(caseId, nodes, edges, issues);
        List<Map<String, Object>> knowledgeRefs =
                knowledgeRefs(caseId, patterns, risks, techniques, nodes);
        List<Map<String, Object>> knowledgeBindings = knowledgeBindings(caseId, techniques);
        Set<String> retainedTypes = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            retainedTypes.add(Objects.toString(
                    ((Map<?, ?>) node.get("properties")).get("canonicalType")));
        }
        int maturityLevel = !matters.isEmpty() ? 5
                : !techniques.isEmpty() ? 4
                : retainedTypes.contains("RISK_HYPOTHESIS") ? 3
                : retainedTypes.contains("BEHAVIOR_PATTERN") ? 2
                : nodes.isEmpty() ? 0 : 1;
        String maturity = maturityLevel == 0 ? "M0_EMPTY" : "M" + maturityLevel + "_" +
                List.of("", "FACT", "DETECTION", "RISK", "AMLTRIX", "NARRATIVE")
                        .get(maturityLevel);
        Map<String, Long> nodeCounts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String canonical = Objects.toString(((Map<?, ?>) node.get("properties")).get("canonicalType"));
            nodeCounts.merge(canonical, 1L, Long::sum);
        }
        Map<String, Object> latest = latestSnapshot(caseId);
        Map<String, Object> summary = props(
                "schemaVersion", SCHEMA_VERSION,
                "formalNodeTypeCount", 14,
                "eventGraphTypeCount", 11,
                "reasoningGraphTypeCount", 3,
                "narrativeObjectCount", matters.size(),
                "knowledgeBindingCount", knowledgeBindings.size(),
                "knowledgeRefCount", knowledgeRefs.size(),
                "boundaryExcludedCount", rejectedBoundaryIds.size(),
                "maturity", maturity,
                "status", issues.stream().anyMatch(i -> "ERROR".equals(i.get("severity")))
                        ? "CANDIDATE" : "ACTIVE",
                "nodeCounts", nodeCounts,
                "nodeCount", nodes.size(),
                "edgeCount", edges.size(),
                "validationIssueCount", issues.size(),
                "validationMetrics", graphValidation.asMap(),
                "domainTypeSummary", props(
                        "eventGraphNodeTypes", 11,
                        "reasoningGraphNodeTypes", 3,
                        "narrativeObjectTypes", 1,
                        "knowledgeDefinitionTypes", 5,
                        "auxiliaryRecordTypes", 4),
                "runtimeObjectCounts", props(
                        "eventGraphNodes", countDomain(nodes, false),
                        "reasoningGraphNodes", countDomain(nodes, true),
                        "narrativeObjects", matters.size(),
                        "knowledgeObjectsReferenced", knowledgeRefs.size(),
                        "auxiliaryRecords", knowledgeBindings.size()),
                "chainId", latest.get("chainId"),
                "revision", latest.get("revision"),
                "projectionStatus", latest.get("projectionStatus"));
        return props("caseId", caseId, "summary", summary, "nodes", nodes, "edges", edges,
                "issues", issues, "nodeTypes", nodeTypeRegistry(),
                "narrativeProjections", matters,
                "knowledgeRefs", knowledgeRefs,
                "knowledgeBindings", knowledgeBindings);
    }

    @Transactional
    public Map<String, Object> snapshot(String caseId) {
        Map<String, Object> graph = graph(caseId);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) graph.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> knowledgeRefs =
                (List<Map<String, Object>>) graph.get("knowledgeRefs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> narratives =
                (List<Map<String, Object>>) graph.get("narrativeProjections");
        Map<String, Object> caseRow = jdbc.queryForMap("""
            SELECT id AS case_sequence_id,bank_code,workspace_id FROM cf_risk_case
            WHERE case_id=? AND deleted=false
            """, caseId);
        Map<String, Object> explanation = jdbc.query("""
            SELECT data_snapshot_sha256,knowledge_release_ref,model_ref
            FROM case_explanation_snapshot WHERE case_id=? ORDER BY revision DESC LIMIT 1
            """, rs -> rs.next() ? props(
                    "input", rs.getString("data_snapshot_sha256"),
                    "knowledge", rs.getString("knowledge_release_ref"),
                    "model", rs.getString("model_ref")) : Map.of(), caseId);
        String input = Objects.toString(explanation.get("input"), sha256(caseId + "|EMPTY"));
        String knowledge = Objects.toString(explanation.get("knowledge"), "UNVERSIONED");
        String model = Objects.toString(explanation.get("model"), "UNKNOWN");
        String content = SNAPSHOT_FORMAT_VERSION + "|" + json(graph.get("nodes")) + "|"
                + json(graph.get("edges")) + "|" + json(graph.get("issues")) + "|"
                + json(summary.get("validationMetrics")) + "|" + input + "|"
                + knowledge + "|" + model;
        String digest = sha256(content);
        Map<String, Object> existing = jdbc.query("""
            SELECT chain_id,revision,maturity,status,edge_count,projection_status
            FROM case_core_chain_snapshot
            WHERE case_id=? AND content_sha256=? AND status<>'SUPERSEDED'
            ORDER BY revision DESC LIMIT 1
            """, rs -> rs.next() ? props(
                    "chainId", rs.getString("chain_id"), "revision", rs.getInt("revision"),
                    "maturity", rs.getString("maturity"), "status", rs.getString("status"),
                    "edgeCount", rs.getInt("edge_count"),
                    "projectionStatus", rs.getString("projection_status"),
                    "unchanged", true) : Map.of(), caseId, digest);
        if (!existing.isEmpty()) return existing;
        Integer revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision),0)+1 FROM case_core_chain_snapshot WHERE case_id=?",
                Integer.class, caseId);
        String chainId = "CHAIN-" + digest.substring(0, 20).toUpperCase() + "-R" + revision;
        jdbc.update("""
            UPDATE case_core_chain_snapshot SET status='SUPERSEDED',superseded_at=CURRENT_TIMESTAMP
            WHERE case_id=? AND status='ACTIVE'
            """, caseId);
        jdbc.update("""
            INSERT INTO case_core_chain_snapshot
              (chain_id,bank_code,workspace_id,case_id,revision,maturity,status,schema_version,
               input_snapshot_sha256,knowledge_release_ref,model_ref,node_counts,edge_count,
               primary_path,validation_issues,validation_metrics,content_sha256,projection_status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?::jsonb,?,'PENDING')
            ON CONFLICT (chain_id) DO NOTHING
            """, chainId, caseRow.get("bank_code"), caseRow.get("workspace_id"), caseId, revision,
                summary.get("maturity"), summary.get("status"), SCHEMA_VERSION, input, knowledge,
                model, json(summary.get("nodeCounts")), edges.size(), "[]",
                json(graph.get("issues")), json(summary.get("validationMetrics")), digest);
        for (Map<String, Object> node : nodes) persistNode(chainId, caseId, caseRow, node);
        for (Map<String, Object> edge : edges) {
            persistEdge(chainId, caseRow, edge);
            persistEventRelation(caseId, caseRow, edge);
        }
        for (Map<String, Object> ref : knowledgeRefs) {
            persistKnowledgeRef(chainId, caseId, caseRow, ref);
        }
        String eventId = "OUTBOX-" + chainId;
        jdbc.update("""
            INSERT INTO case_core_chain_outbox
              (event_id,bank_code,workspace_id,case_id,chain_id,event_type,payload)
            VALUES (?,?,?,?,?,'CORE_CHAIN_PROJECT',?::jsonb)
            ON CONFLICT (event_id) DO NOTHING
            """, eventId, caseRow.get("bank_code"), caseRow.get("workspace_id"), caseId,
                chainId, json(props("chainId", chainId, "contentSha256", digest)));
        persistMatterClaims(caseId, caseRow);
        persistNarrativeRefs(chainId, caseId, caseRow, narratives);
        return props("chainId", chainId, "revision", revision,
                "maturity", summary.get("maturity"), "status", summary.get("status"),
                "nodeCount", summary.get("nodeCount"), "edgeCount", edges.size(),
                "validationIssues", graph.get("issues"), "projectionStatus", "PENDING");
    }

    private void appendIndicators(String caseId, Set<String> ids,
                                  List<Map<String, Object>> nodes, Set<String> nodeIds,
                                  List<Map<String, Object>> edges) {
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>(ids);
        List<Map<String, Object>> rows = normalize(jdbc.queryForList("""
            SELECT c.calculation_id,c.indicator_code,c.indicator_version,c.numeric_value,
                   c.risk_level,c.input_snapshot_sha256,c.explanation,d.indicator_name
            FROM indicator_calculation_result c
            LEFT JOIN indicator_definition d
              ON d.indicator_code=c.indicator_code
            WHERE c.calculation_id IN (%s)
            """.formatted(placeholders), args.toArray()), "explanation");
        for (Map<String, Object> row : rows) {
            String id = text(row, "calculation_id");
            String graphId = scoped(caseId, id);
            String code = text(row, "indicator_code");
            String version = text(row, "indicator_version");
            addNode(nodes, nodeIds, graphId, "INDICATORRESULT", "INDICATOR_RESULT",
                    "DETECTION_REASONING", first(row, "indicator_name", "indicator_code"),
                    withCommon(row, "INDICATOR_RESULT", "CALCULATED"));
            JsonNode explanation = mapper.valueToTree(row.get("explanation"));
            for (JsonNode eventRef : explanation.path("eventRefs")) {
                String eventId = eventRef.asText();
                if (!nodeIds.contains(eventId)) continue;
                addEdge(edges, eventId, "EVENT", "INPUT_TO_INDICATOR_RESULT",
                        graphId, "INDICATOR_RESULT", "INFERENCE", "INPUT",
                        "TEXT_INDICATOR_EXTRACTOR", false);
            }
        }
    }

    /**
     * Materialises source evidence in the frozen chain.  Text cases use the
     * submitted report as evidence for their extracted events; transaction
     * cases retain the signal-to-event assignment used by the TuGraph writer.
     * Explicit event evidence_refs always take precedence and are preserved.
     */
    private void appendEvidence(String caseId, Map<String, Object> context,
                                List<Map<String, Object>> events,
                                List<Map<String, Object>> nodes, Set<String> nodeIds,
                                List<Map<String, Object>> edges,
                                boolean hasWorkerEvidence) {
        if (events.isEmpty()) return;
        Set<String> linkedEvidence = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            String eventId = text(event, "event_id");
            for (String evidenceRef : refs(event.get("evidence_refs"))) {
                String evidenceId = evidenceRef.startsWith("EVID-")
                        ? evidenceRef : "EVID-REF-" + evidenceRef;
                appendEvidenceNodeAndEdge(evidenceId, evidenceRef, "EVENT_SOURCE_REF",
                        eventId, nodes, nodeIds, edges, linkedEvidence);
            }
        }

        String caseSource = Objects.toString(context.get("case_source"), "");
        if ("TEXT_CASE".equalsIgnoreCase(caseSource)) {
            if (hasWorkerEvidence) return;
            String evidenceId = "EVID-" + caseId;
            for (Map<String, Object> event : events) {
                appendEvidenceNodeAndEdge(evidenceId, caseId, "SOURCE_DOCUMENT",
                        text(event, "event_id"), nodes, nodeIds, edges, linkedEvidence);
            }
            return;
        }

        List<Map<String, Object>> signals = jdbc.queryForList("""
            SELECT s.signal_id
            FROM case_signal_rel r
            JOIN risk_signal s ON s.signal_id=r.signal_id
            JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
            WHERE r.case_id=?
            ORDER BY t.id,s.signal_id
            """, caseId);
        for (int index = 0; index < signals.size(); index++) {
            String signalId = text(signals.get(index), "signal_id");
            if (signalId.isBlank()) continue;
            String eventId = text(events.get(index % events.size()), "event_id");
            appendEvidenceNodeAndEdge("EVID-SIGNAL-" + signalId, signalId,
                    "RISK_SIGNAL", eventId, nodes, nodeIds, edges, linkedEvidence);
        }
    }

    /**
     * Uses the same unstructured-worker facts as the Evidence, Customer and
     * Account model tabs.  The core-chain snapshot must not invent a second,
     * reduced view of the case facts.
     */
    private boolean appendWorkerFacts(String caseId, JsonNode output,
                                      List<Map<String, Object>> events,
                                      List<Map<String, Object>> nodes, Set<String> nodeIds,
                                      List<Map<String, Object>> edges) {
        JsonNode workerNodes = output.path("nodes");
        if (!workerNodes.isObject()) return false;

        Map<String, String> idMap = new LinkedHashMap<>();
        JsonNode workerCases = workerNodes.path("cases");
        if (workerCases.isArray() && !workerCases.isEmpty()) {
            idMap.put(workerCases.get(0).path("uid").asText(), caseId);
        }

        List<JsonNode> workerEvents = new ArrayList<>();
        workerNodes.path("events").forEach(workerEvents::add);
        Set<String> usedEventIds = new LinkedHashSet<>();
        for (int i = 0; i < workerEvents.size(); i++) {
            JsonNode workerEvent = workerEvents.get(i);
            String workerId = workerEvent.path("uid").asText();
            String workerName = normalizeBusinessText(workerEvent.path("name").asText());
            String eventId = "";
            for (Map<String, Object> event : events) {
                String candidateId = text(event, "event_id");
                if (usedEventIds.contains(candidateId)) continue;
                if (workerName.equals(normalizeBusinessText(text(event, "event_name")))) {
                    eventId = candidateId;
                    break;
                }
            }
            if (eventId.isBlank() && i < events.size()) eventId = text(events.get(i), "event_id");
            if (!workerId.isBlank() && !eventId.isBlank()) {
                idMap.put(workerId, eventId);
                usedEventIds.add(eventId);
            }
        }

        appendWorkerNodeGroup(workerNodes.path("customers"), "CUSTOMER", "CUSTOMER",
                caseId, nodes, nodeIds, idMap);
        appendWorkerNodeGroup(workerNodes.path("accounts"), "ACCOUNT", "ACCOUNT",
                caseId, nodes, nodeIds, idMap);
        boolean hasEvidence = appendWorkerNodeGroup(workerNodes.path("evidences"),
                "EVIDENCE", "EVIDENCE", caseId, nodes, nodeIds, idMap);
        appendWorkerScopeEdges(caseId, workerNodes.path("customers"), idMap,
                "INVOLVES_SUBJECT", "CUSTOMER", edges);
        appendWorkerScopeEdges(caseId, workerNodes.path("accounts"), idMap,
                "INVOLVES_ASSET", "ACCOUNT", edges);

        JsonNode workerEdges = output.path("edges");
        if (!workerEdges.isObject()) return hasEvidence;
        List<String> workerRelationTypes = List.of(
                "owns_account", "controls_account", "involves_account",
                "involves_customer", "related_to_customer", "contains_evidence",
                "contains_event", "precedes_event");
        workerRelationTypes.forEach(workerType -> {
            JsonNode group = workerEdges.path(workerType);
            if (!group.isArray()) return;
            group.forEach(item -> {
                String relation = workerRelationType(workerType, item);
                if (relation.isBlank()) return;
                String source = idMap.getOrDefault(item.path("source_uid").asText(), "");
                String target = idMap.getOrDefault(item.path("target_uid").asText(), "");
                if (source.isBlank() || target.isBlank()) return;
                String sourceType = canonicalTypeForId(nodes, source);
                String targetType = canonicalTypeForId(nodes, target);
                addEdge(edges, source, sourceType, relation, target, targetType,
                        "FACT", relation.equals("EVIDENCE_SUPPORTS") ? "EVIDENCE" : "CONTEXT",
                        "WORKER_EXTRACTION", false);
                @SuppressWarnings("unchecked")
                Map<String, Object> edgeProperties =
                        (Map<String, Object>) edges.get(edges.size() - 1).get("properties");
                edgeProperties.put("sourceRef", item.path("relation_uid").asText());
                item.fields().forEachRemaining(field -> {
                    if (!Set.of("source_uid", "target_uid", "relation_uid").contains(field.getKey())
                            && !field.getValue().isNull() && !field.getValue().asText().isBlank()) {
                        edgeProperties.put(field.getKey(), jsonValue(field.getValue()));
                    }
                });
                if ("EVIDENCE_SUPPORTS".equals(relation)) {
                    edgeProperties.put("evidenceRefs", List.of(source));
                }
            });
        });

        // A proof node should answer “what does it prove?” without requiring
        // the user to decode its outgoing edge.
        Map<String, String> nodeNames = new LinkedHashMap<>();
        nodes.forEach(node -> nodeNames.put(Objects.toString(node.get("id"), ""),
                Objects.toString(node.get("name"), "")));
        for (Map<String, Object> edge : edges) {
            if (!"EVIDENCE_SUPPORTS".equals(Objects.toString(edge.get("type"), ""))
                    || !"EVENT".equals(Objects.toString(
                    ((Map<?, ?>) edge.get("properties")).get("targetType"), ""))) continue;
            String source = Objects.toString(edge.get("source"), "");
            String target = Objects.toString(edge.get("target"), "");
            nodes.stream().filter(node -> source.equals(Objects.toString(node.get("id"), "")))
                    .findFirst().ifPresent(node -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> properties =
                                (Map<String, Object>) node.get("properties");
                        properties.put("proves", "证明事件：" + nodeNames.getOrDefault(target, target));
                        properties.put("relatedEventId", target);
                        properties.put("businessMeaning",
                                "该证据用于证明“" + nodeNames.getOrDefault(target, target) + "”确有文本事实依据。");
                    });
        }
        return hasEvidence;
    }

    private void appendWorkerScopeEdges(String caseId, JsonNode group,
                                        Map<String, String> idMap,
                                        String relation, String targetType,
                                        List<Map<String, Object>> edges) {
        if (!group.isArray()) return;
        group.forEach(item -> {
            String target = idMap.getOrDefault(item.path("uid").asText(), "");
            if (target.isBlank()) return;
            addEdge(edges, caseId, "CASE", relation, target, targetType,
                    "FACT", "SCOPE", "WORKER_EXTRACTION", false);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) edges.get(edges.size() - 1).get("properties");
            properties.put("sourceRef", item.path("uid").asText());
        });
    }

    private String workerRelationType(String workerType, JsonNode item) {
        String canonicalType = item.path("relation_type").asText("").trim();
        if (!canonicalType.isBlank()) {
            return canonicalType;
        }
        return switch (workerType) {
            case "owns_account" -> "OWNS_ACCOUNT";
            case "controls_account" -> "CONTROLS";
            case "involves_account" -> accountRoleRelation(item);
            case "involves_customer" -> subjectRoleRelation(item);
            case "related_to_customer" -> "ASSOCIATED_WITH";
            case "contains_evidence" -> "HAS_EVIDENCE";
            case "contains_event" -> "EVIDENCE_SUPPORTS";
            case "precedes_event" -> "PRECEDES";
            default -> "";
        };
    }

    private String subjectRoleRelation(JsonNode item) {
        String role = normalizeBusinessText(item.path("role").asText());
        if (role.contains("受益")) return "BENEFICIARY";
        if (role.contains("持有人") || role.contains("发起") || role.contains("操作")
                || role.contains("行为")) return "ACTOR";
        return "SUBJECT";
    }

    private String accountRoleRelation(JsonNode item) {
        String role = normalizeBusinessText(item.path("role").asText());
        String side = normalizeBusinessText(item.path("event_side").asText());
        String semantics = role + side;
        if (semantics.contains("来源") || semantics.contains("付款")
                || semantics.contains("转出")) return "SOURCE_ACCOUNT";
        if (semantics.contains("目标") || semantics.contains("收款")
                || semantics.contains("转入") || semantics.contains("受益")) return "TARGET_ACCOUNT";
        return "SUBJECT_ACCOUNT";
    }

    private boolean appendWorkerNodeGroup(JsonNode group, String legacyType,
                                          String canonicalType, String caseId,
                                          List<Map<String, Object>> nodes, Set<String> nodeIds,
                                          Map<String, String> idMap) {
        if (!group.isArray() || group.isEmpty()) return false;
        group.forEach(item -> {
            String id = item.path("uid").asText();
            if (id.isBlank()) return;
            Map<String, Object> properties = mapper.convertValue(item, LinkedHashMap.class);
            String name;
            String epistemicType = "OBSERVED";
            if ("ACCOUNT".equals(canonicalType)) {
                String holderName = text(properties, "holder_name");
                String accountNo = text(properties, "account_no");
                name = accountInstanceName(holderName, accountNo);
                properties.put("displayName", name);
                properties.put("identityStatus",
                        accountNo.isBlank() ? "PARTIAL_IDENTITY" : "IDENTIFIED");
                properties.put("businessMeaning", accountNo.isBlank()
                        ? "材料明确提及“" + fallback(holderName, "未知持有人")
                                + "”名下账户，但未提供账号；这是待核实账户实例，"
                                + "与同名客户节点不是同一对象。"
                        : "账号为 " + accountNo + "、持有人为“"
                                + fallback(holderName, "待确认") + "”的涉案账户；"
                                + "账户节点与持有人客户节点分别建模。");
                if (accountNo.isBlank()) epistemicType = "REPORTED";
            } else {
                name = first(properties, "name", "summary", "uid");
                if ("CUSTOMER".equals(canonicalType)) {
                    properties.put("businessMeaning", "参与案件事件或持有涉案账户的客户实例");
                } else if ("EVIDENCE".equals(canonicalType)) {
                    String summary = first(properties, "summary", "name");
                    name = EvidenceTitleNormalizer.meaningfulTitle(name, summary);
                    properties.put("name", name);
                    properties.put("evidenceSummary", summary);
                    properties.put("proves", "待通过证据关系关联具体事件");
                    properties.put("businessMeaning", "该证据表明：" + summary);
                }
            }
            addNode(nodes, nodeIds, id, legacyType, canonicalType, "FACT",
                    name, withCommon(properties, canonicalType, epistemicType));
            idMap.put(id, id);
        });
        return true;
    }

    private String accountInstanceName(String holderName, String accountNo) {
        if (!accountNo.isBlank()) {
            String visible = accountNo.replaceAll("[^A-Za-z0-9]", "");
            String suffix = visible.length() <= 4
                    ? visible : visible.substring(visible.length() - 4);
            return "账户" + fallback(suffix, "号待核")
                    + "（" + fallback(holderName, "持有人待确认") + "）";
        }
        return "待核账户（" + fallback(holderName, "持有人未知") + "，账号未披露）";
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonNode latestWorkerOutput(String caseId) {
        try {
            List<String> rows = jdbc.queryForList("""
                SELECT s.result_json::text
                FROM case_analysis_job_rel rel
                JOIN analysis_job j ON j.job_id=rel.job_id
                JOIN analysis_job_step s ON s.job_id=j.job_id
                WHERE rel.case_id=?
                  AND s.step_order=(SELECT MAX(s2.step_order)
                                    FROM analysis_job_step s2 WHERE s2.job_id=j.job_id)
                  AND s.status='SUCCEEDED'
                ORDER BY s.completed_at DESC NULLS LAST LIMIT 1
                """, String.class, caseId);
            if (rows.isEmpty()) return mapper.createObjectNode();
            JsonNode root = mapper.readTree(rows.get(0));
            JsonNode worker = root.has("workerResult") ? root.path("workerResult") : root;
            JsonNode output = worker.path("final").path("output");
            return output.path("nodes").isObject() ? output : worker;
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    /**
     * Worker evidence may describe either one event or the case-wide risk
     * conclusion.  Event evidence is already connected through EVIDENCE_SUPPORTS;
     * case-wide evidence must support the concrete risk-hypothesis instance.
     * This preserves all evidence-model instances without creating semantic
     * orphan vertices.
     */
    @SuppressWarnings("unchecked")
    private void completeEvidenceMeaning(String caseId,
                                         List<Map<String, Object>> events,
                                         List<Map<String, Object>> risks,
                                         List<Map<String, Object>> nodes,
                                         List<Map<String, Object>> edges) {
        Set<String> evidenceWithSemanticTarget = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            if ("EVIDENCE_SUPPORTS".equals(Objects.toString(edge.get("type"), ""))
                    && "EVIDENCE".equals(Objects.toString(
                    ((Map<?, ?>) edge.get("properties")).get("sourceType"), ""))) {
                evidenceWithSemanticTarget.add(Objects.toString(edge.get("source"), ""));
            }
        }
        Map<String, String> nodeNames = new LinkedHashMap<>();
        nodes.forEach(node -> nodeNames.put(Objects.toString(node.get("id"), ""),
                Objects.toString(node.get("name"), "")));
        String riskId = risks.isEmpty() ? "" : scoped(caseId, text(risks.get(0), "risk_event_id"));
        String riskName = nodeNames.getOrDefault(riskId, "");
        String fallbackEventId = events.isEmpty() ? "" : text(events.get(0), "event_id");
        String fallbackEventName = nodeNames.getOrDefault(fallbackEventId, "");

        for (Map<String, Object> node : nodes) {
            if (!"EVIDENCE".equals(canonicalType(node))) continue;
            String evidenceId = Objects.toString(node.get("id"), "");
            if (evidenceWithSemanticTarget.contains(evidenceId)) continue;
            Map<String, Object> properties = (Map<String, Object>) node.get("properties");
            if (!riskId.isBlank()) {
                addEdge(edges, evidenceId, "EVIDENCE", "EVIDENCE_SUPPORTS",
                        riskId, "RISK_HYPOTHESIS", "INFERENCE", "EVIDENCE",
                        "WORKER_EXTRACTION", false);
                ((Map<String, Object>) edges.get(edges.size() - 1).get("properties"))
                        .put("evidenceRefs", List.of(evidenceId));
                properties.put("proves", "支持风险判断：" + riskName);
                properties.put("relatedRiskHypothesisId", riskId);
                properties.put("businessMeaning",
                        "该证据从案件整体层面支持“" + riskName + "”这一风险判断。");
            } else if (!fallbackEventId.isBlank()) {
                addEdge(edges, evidenceId, "EVIDENCE", "EVIDENCE_SUPPORTS",
                        fallbackEventId, "EVENT", "FACT", "EVIDENCE",
                        "WORKER_EXTRACTION", false);
                ((Map<String, Object>) edges.get(edges.size() - 1).get("properties"))
                        .put("evidenceRefs", List.of(evidenceId));
                properties.put("proves", "支持事件：" + fallbackEventName);
                properties.put("relatedEventId", fallbackEventId);
                properties.put("businessMeaning",
                        "该证据为“" + fallbackEventName + "”提供案件文本依据。");
            }
        }
    }

    private Object jsonValue(JsonNode value) {
        return value.isValueNode() ? value.asText() : mapper.convertValue(value, Object.class);
    }

    private String canonicalTypeForId(List<Map<String, Object>> nodes, String id) {
        return nodes.stream()
                .filter(node -> id.equals(Objects.toString(node.get("id"), "")))
                .map(this::canonicalType).findFirst().orElse("");
    }

    private String normalizeBusinessText(String value) {
        return Objects.toString(value, "").replaceAll("[\\s，。；：、,.!?！？:;（）()\\-]", "");
    }

    @SuppressWarnings("unchecked")
    private void appendEvidenceNodeAndEdge(String evidenceId, String sourceRef,
                                           String evidenceClass, String eventId,
                                           List<Map<String, Object>> nodes, Set<String> nodeIds,
                                           List<Map<String, Object>> edges,
                                           Set<String> linkedEvidence) {
        addNode(nodes, nodeIds, evidenceId, "EVIDENCE", "EVIDENCE", "FACT",
                "证据 " + abbreviateLabel(sourceRef),
                props("canonicalType", "EVIDENCE", "epistemicType", "OBSERVED",
                        "evidenceClass", evidenceClass, "sourceRef", sourceRef,
                        "contentSha256", sha256(evidenceClass + "|" + sourceRef)));
        String edgeKey = evidenceId + "|" + eventId;
        if (!linkedEvidence.add(edgeKey)) return;
        addEdge(edges, evidenceId, "EVIDENCE", "EVIDENCE_SUPPORTS", eventId, "EVENT",
                "FACT", "SUPPORT", "SOURCE_REF", false);
        Map<String, Object> edgeProperties =
                (Map<String, Object>) edges.get(edges.size() - 1).get("properties");
        edgeProperties.put("evidenceRefs", List.of(sourceRef));
    }

    private String abbreviateLabel(String value) {
        if (value == null || value.isBlank()) return "来源";
        return value.length() <= 24 ? value : value.substring(0, 24) + "...";
    }

    private void appendEventRelations(List<Map<String, Object>> events,
                                      List<Map<String, Object>> edges) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> event : events) byId.put(text(event, "event_id"), event);
        List<Map<String, Object>> persisted = events.isEmpty() ? List.of() : normalize(
                jdbc.queryForList("""
                    SELECT source_event_id,relation_type,target_event_id,edge_origin,source_ref
                    FROM case_event_relation
                    WHERE case_id=(SELECT case_id FROM cf_risk_event WHERE event_id=?)
                    ORDER BY relation_type,source_event_id,target_event_id
                    """, text(events.get(0), "event_id")), "source_ref");
        Set<String> precise = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            String source = text(edge, "source");
            String target = text(edge, "target");
            String type = text(edge, "type");
            if (byId.containsKey(source) && byId.containsKey(target)) {
                precise.add(source + "|" + type + "|" + target);
            }
        }
        for (Map<String, Object> row : persisted) {
            String source = text(row, "source_event_id");
            String target = text(row, "target_event_id");
            String type = text(row, "relation_type");
            if (!byId.containsKey(source) || !byId.containsKey(target)) continue;
            if (!precise.add(source + "|" + type + "|" + target)) continue;
            addEdge(edges, source, "EVENT", type, target, "EVENT",
                    "FACT", "EVENT_SEQUENCE", text(row, "edge_origin"), false);
        }
        // A timestamp establishes ordering, not causality. Only adjacent events
        // are materialized to avoid an O(N²) transitive closure.
        for (int i = 1; i < events.size(); i++) {
            Map<String, Object> previous = events.get(i - 1);
            Map<String, Object> current = events.get(i);
            if (previous.get("event_time") == null || current.get("event_time") == null) continue;
            String source = text(previous, "event_id");
            String target = text(current, "event_id");
            String key = source + "|PRECEDES|" + target;
            if (precise.add(key)) addEdge(edges, source, "EVENT", "PRECEDES",
                    target, "EVENT", "FACT", "EVENT_SEQUENCE",
                    "EVENT_TIME_ORDER", false);
        }
    }

    private List<Map<String, Object>> knowledgeRefs(String caseId,
                                                     List<Map<String, Object>> patterns,
                                                     List<Map<String, Object>> risks,
                                                     List<Map<String, Object>> techniques,
                                                     List<Map<String, Object>> nodes) {
        String releaseRef = jdbc.query("""
            SELECT knowledge_release_ref FROM case_explanation_snapshot
            WHERE case_id=? ORDER BY revision DESC LIMIT 1
            """, rs -> rs.next() ? Objects.toString(rs.getString(1), "UNVERSIONED")
                    : "UNVERSIONED", caseId);
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> row : patterns) {
            addKnowledgeRef(refs, "BEHAVIOR_PATTERN",
                    scoped(caseId, text(row, "occurrence_id")), "PATTERN",
                    text(row, "pattern_code"), text(row, "pattern_version"),
                    releaseRef, "EXACT_CODE", 1.0);
        }
        for (Map<String, Object> row : risks) {
            addKnowledgeRef(refs, "RISK_HYPOTHESIS",
                    scoped(caseId, text(row, "risk_event_id")), "RISK_CATEGORY",
                    text(row, "risk_event_type"), text(row, "risk_event_type_version"),
                    releaseRef, text(row, "definition_binding_method"),
                    row.get("definition_binding_confidence"));
        }
        for (Map<String, Object> row : techniques) {
            for (String riskId : refs(row.get("risk_event_refs"))) {
                addKnowledgeRef(refs, "RISK_HYPOTHESIS", scoped(caseId, riskId),
                        "TECHNIQUE", text(row, "technique_code"),
                        text(row, "technique_version"), releaseRef,
                        "KNOWLEDGE_BINDING_RECORD", row.get("mapping_confidence"));
            }
        }
        for (Map<String, Object> node : nodes) {
            if (!"INDICATOR_RESULT".equals(canonicalType(node))) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) node.get("properties");
            addKnowledgeRef(refs, "INDICATOR_RESULT", Objects.toString(node.get("id"), ""),
                    "INDICATOR", Objects.toString(properties.get("indicator_code"), ""),
                    Objects.toString(properties.get("indicator_version"), ""),
                    releaseRef, "EXACT_CODE", 1.0);
        }
        return enrichKnowledgeRefs(refs);
    }

    private void addKnowledgeRef(List<Map<String, Object>> refs, String ownerType,
                                 String ownerId, String definitionType, String code,
                                 String version, String releaseRef, String bindingMethod,
                                 Object bindingConfidence) {
        if (ownerId.isBlank() || code.isBlank() || version.isBlank()) return;
        String definitionDigest = sha256(definitionType + "|" + code + "|" + version);
        refs.add(props(
                "refId", "KREF-" + sha256(ownerType + "|" + ownerId + "|"
                        + definitionType + "|" + code + "|" + version).substring(0, 24).toUpperCase(),
                "ownerType", ownerType, "ownerId", ownerId,
                "definitionType", definitionType, "code", code, "version", version,
                "releaseRef", releaseRef, "effectiveTime", null,
                "contentSha256", definitionDigest,
                "bindingMethod", bindingMethod,
                "bindingConfidence", bindingConfidence));
    }

    private int countDomain(List<Map<String, Object>> nodes, boolean reasoning) {
        Set<String> reasoningTypes = Set.of("BEHAVIOR_PATTERN",
                "RISK_HYPOTHESIS", "INVESTIGATION_HYPOTHESIS");
        int count = 0;
        for (Map<String, Object> node : nodes) {
            if (reasoningTypes.contains(canonicalType(node)) == reasoning) count++;
        }
        return count;
    }

    private void appendInvestigationHypotheses(String caseId,
                                                List<Map<String, Object>> nodes,
                                                Set<String> nodeIds,
                                                List<Map<String, Object>> edges) {
        List<Map<String, Object>> rows = normalize(jdbc.queryForList("""
            SELECT hypothesis_id,risk_event_id,hypothesis,evidence_needed,
                   recommended_actions,priority,status
            FROM investigation_hypothesis
            WHERE case_id=? AND status IN ('OPEN','ACKNOWLEDGED')
            ORDER BY hypothesis_id
            """, caseId), "evidence_needed", "recommended_actions");
        for (Map<String, Object> row : rows) {
            String id = scoped(caseId, text(row, "hypothesis_id"));
            String riskId = text(row, "risk_event_id");
            if (riskId.isBlank()) continue;
            List<String> evidenceNeeded = refs(row.get("evidence_needed"));
            List<String> actions = refs(row.get("recommended_actions"));
            Map<String, Object> properties =
                    withCommon(row, "INVESTIGATION_HYPOTHESIS", "INFERRED");
            properties.put("instanceQuestion", "下一步需要核验什么？");
            properties.put("businessMeaning",
                    "该实例把风险假设转化为一个可执行的调查问题："
                            + text(row, "hypothesis"));
            properties.put("supportSummary",
                    "待取得证据：" + joinOrNone(evidenceNeeded)
                            + "；建议动作：" + joinOrNone(actions) + "。");
            properties.put("decisionBoundary",
                    "这是待办核验问题，不是风险结论；状态 "
                            + first(row, "status") + "，优先级 "
                            + first(row, "priority") + "。");
            addNode(nodes, nodeIds, id, "INVESTIGATIONHYPOTHESIS",
                    "INVESTIGATION_HYPOTHESIS", "REASONING_GRAPH",
                    first(row, "hypothesis", "hypothesis_id"),
                    properties);
            addEdge(edges, scoped(caseId, riskId), "RISK_HYPOTHESIS",
                    "RAISES_INVESTIGATION_HYPOTHESIS", id,
                    "INVESTIGATION_HYPOTHESIS", "INFERENCE", "FOLLOW_UP",
                    "RULE", false);
        }
    }

    private List<Map<String, Object>> knowledgeBindings(String caseId,
                                                        List<Map<String, Object>> techniques) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : techniques) {
            List<String> riskRefs = refs(source.get("risk_event_refs"));
            if (riskRefs.isEmpty()) continue;
            Map<String, Object> binding = new LinkedHashMap<>(source);
            binding.put("bindingId", source.get("occurrence_id"));
            binding.put("bindingType", "KnowledgeBindingRecord");
            binding.put("riskHypothesisRefs", scopedRefs(caseId, riskRefs));
            binding.put("knowledgeRef", props(
                    "type", "TECHNIQUE",
                    "code", source.get("technique_code"),
                    "version", source.get("technique_version")));
            result.add(binding);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String canonicalType(Map<String, Object> node) {
        Object properties = node.get("properties");
        if (!(properties instanceof Map<?, ?> map)) return "";
        return Objects.toString(map.get("canonicalType"), "");
    }

    @SuppressWarnings("unchecked")
    private void persistNode(String chainId, String caseId, Map<String, Object> caseRow,
                             Map<String, Object> node) {
        Map<String, Object> properties = (Map<String, Object>) node.get("properties");
        String nodeKey = Objects.toString(node.get("id"), "");
        String digest = sha256(nodeKey + "|" + json(properties));
        jdbc.update("""
            INSERT INTO case_core_chain_node
              (chain_id,node_key,bank_code,workspace_id,case_id,canonical_type,
               legacy_type,graph_plane,display_name,business_id,properties,content_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
            ON CONFLICT (chain_id,node_key) DO NOTHING
            """, chainId, nodeKey, caseRow.get("bank_code"), caseRow.get("workspace_id"),
                caseId, properties.get("canonicalType"), node.get("label"),
                properties.get("graphPlane"), node.get("name"), properties.get("businessId"),
                json(properties), digest);
    }

    private void persistKnowledgeRef(String chainId, String caseId,
                                     Map<String, Object> caseRow,
                                     Map<String, Object> ref) {
        jdbc.update("""
            INSERT INTO case_core_chain_knowledge_ref
              (chain_id,ref_id,bank_code,workspace_id,case_id,owner_type,owner_id,
               definition_type,definition_code,definition_version,release_ref,
               effective_time,definition_sha256,binding_method,binding_confidence,
               definition_name,definition_description,classification_path)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,?::jsonb)
            ON CONFLICT (chain_id,ref_id) DO NOTHING
            """, chainId, ref.get("refId"), caseRow.get("bank_code"),
                caseRow.get("workspace_id"), caseId, ref.get("ownerType"),
                ref.get("ownerId"), ref.get("definitionType"), ref.get("code"),
                ref.get("version"), ref.get("releaseRef"), ref.get("contentSha256"),
                ref.get("bindingMethod"), ref.get("bindingConfidence"),
                ref.get("definitionName"), ref.get("definitionDescription"),
                json(ref.getOrDefault("tactics", List.of())));
    }

    @SuppressWarnings("unchecked")
    private void persistEventRelation(String caseId, Map<String, Object> caseRow,
                                      Map<String, Object> edge) {
        String relation = Objects.toString(edge.get("type"), "");
        if (!Set.of("PRECEDES", "FUNDS_FLOW_TO", "SAME_SESSION_NEXT").contains(relation)) return;
        Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
        String source = Objects.toString(edge.get("source"), "");
        String target = Objects.toString(edge.get("target"), "");
        String digest = sha256(caseId + "|" + source + "|" + relation + "|" + target);
        jdbc.update("""
            INSERT INTO case_event_relation
              (relation_id,bank_code,workspace_id,case_id,source_event_id,relation_type,
               target_event_id,edge_origin,source_ref,content_sha256)
            VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)
            ON CONFLICT (bank_code,workspace_id,case_id,source_event_id,relation_type,target_event_id)
            DO NOTHING
            """, "EREL-" + digest.substring(0, 24).toUpperCase(),
                caseRow.get("bank_code"), caseRow.get("workspace_id"), caseId,
                source, relation, target, properties.get("edgeOrigin"),
                json(props("origin", properties.get("edgeOrigin"))), digest);
    }

    private void persistNarrativeRefs(String chainId, String caseId,
                                      Map<String, Object> caseRow,
                                      List<Map<String, Object>> narratives) {
        for (Map<String, Object> matter : narratives) {
            String matterId = text(matter, "matter_id");
            List<Map<String, String>> supporting = new ArrayList<>();
            addTypedRefs(supporting, "EVENT", refs(matter.get("event_refs")));
            addTypedRefs(supporting, "INDICATOR_RESULT",
                    scopedRefs(caseId, refs(matter.get("indicator_result_refs"))));
            addTypedRefs(supporting, "BEHAVIOR_PATTERN",
                    scopedRefs(caseId, refs(matter.get("behavior_occurrence_refs"))));
            addTypedRefs(supporting, "RISK_HYPOTHESIS",
                    scopedRefs(caseId, refs(matter.get("risk_event_refs"))));
            List<String> claimRefs = jdbc.queryForList(
                    "SELECT claim_id FROM case_matter_claim WHERE matter_id=? ORDER BY claim_order",
                    String.class, matterId);
            String digest = sha256(chainId + "|" + matterId + "|" + json(supporting));
            jdbc.update("""
                INSERT INTO case_core_chain_narrative_ref
                  (chain_id,matter_id,bank_code,workspace_id,case_id,claim_refs,
                   explains_risk_refs,supporting_refs,contradicting_refs,content_sha256)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'[]'::jsonb,?)
                ON CONFLICT (chain_id,matter_id) DO NOTHING
                """, chainId, matterId, caseRow.get("bank_code"),
                    caseRow.get("workspace_id"), caseId, json(claimRefs),
                    json(scopedRefs(caseId, refs(matter.get("risk_event_refs")))),
                    json(supporting), digest);
        }
    }

    private void persistEdge(String chainId, Map<String, Object> caseRow, Map<String, Object> edge) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
        String source = Objects.toString(edge.get("source"));
        String target = Objects.toString(edge.get("target"));
        String relation = Objects.toString(edge.get("type"));
        String digest = sha256(chainId + "|" + source + "|" + relation + "|" + target
                + "|" + json(properties));
        Object evidenceRefs = properties.getOrDefault("evidenceRefs", List.of());
        Object ruleRef = properties.get("ruleRef");
        Object confidence = properties.get("confidence");
        jdbc.update("""
            INSERT INTO case_core_chain_edge
              (edge_id,chain_id,bank_code,workspace_id,source_type,source_id,relation_type,
               target_type,target_id,edge_category,path_role,edge_origin,evidence_refs,
               rule_ref,confidence,business_id,content_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
            ON CONFLICT (edge_id) DO NOTHING
            """, "EDGE-" + digest.substring(0, 28).toUpperCase(), chainId,
                caseRow.get("bank_code"), caseRow.get("workspace_id"),
                properties.get("sourceType"), source, relation, properties.get("targetType"),
                target, properties.get("edgeCategory"), properties.get("pathRole"),
                properties.get("edgeOrigin"), json(evidenceRefs), ruleRef, confidence,
                properties.get("businessId"), digest);
    }

    private void persistMatterClaims(String caseId, Map<String, Object> caseRow) {
        List<Map<String, Object>> matters = normalize(jdbc.queryForList("""
            SELECT matter_id,summary,certainty,event_refs,behavior_occurrence_refs,risk_event_refs,
                   technique_occurrence_refs
            FROM case_matter_explanation WHERE case_id=? AND status='ACTIVE'
            """, caseId), "event_refs", "behavior_occurrence_refs", "risk_event_refs",
                "technique_occurrence_refs");
        for (Map<String, Object> matter : matters) {
            List<Map<String, String>> supporting = new ArrayList<>();
            addTypedRefs(supporting, "BASE_EVENT", refs(matter.get("event_refs")));
            addTypedRefs(supporting, "PATTERN_OCCURRENCE",
                    scopedRefs(caseId, refs(matter.get("behavior_occurrence_refs"))));
            addTypedRefs(supporting, "RISK_EVENT_HYPOTHESIS",
                    scopedRefs(caseId, refs(matter.get("risk_event_refs"))));
            addTypedRefs(supporting, "TECHNIQUE_OCCURRENCE",
                    scopedRefs(caseId, refs(matter.get("technique_occurrence_refs"))));
            String matterId = text(matter, "matter_id");
            String summary = text(matter, "summary");
            String digest = sha256(matterId + "|" + summary + "|" + json(supporting));
            jdbc.update("""
                INSERT INTO case_matter_claim
                  (claim_id,matter_id,bank_code,workspace_id,claim_order,claim_type,claim_text,
                   certainty,supporting_refs,contradicting_refs,content_sha256)
                VALUES (?,?,?,?,1,'SUMMARY',?,?,?::jsonb,'[]'::jsonb,?)
                ON CONFLICT (matter_id,claim_order) DO UPDATE SET
                  claim_text=EXCLUDED.claim_text,supporting_refs=EXCLUDED.supporting_refs,
                  content_sha256=EXCLUDED.content_sha256
                """, "CLAIM-" + digest.substring(0, 24).toUpperCase(), matterId,
                    caseRow.get("bank_code"), caseRow.get("workspace_id"), summary,
                    Objects.toString(matter.get("certainty"), "HYPOTHESIS"), json(supporting), digest);
        }
    }

    private List<Map<String, Object>> nodeTypeRegistry() {
        return normalize(jdbc.queryForList("""
            SELECT node_type AS "nodeType",display_name AS "displayName",plane,
                   epistemic_type AS "epistemicType",visibility_policy AS "visibilityPolicy"
            FROM graph_node_type_registry
            WHERE status='ACTIVE' AND schema_version='1.8'
            ORDER BY plane,node_type
                """), "visibilityPolicy");
    }

    private List<Map<String, Object>> frozenKnowledgeRefs(String chainId) {
        List<Map<String, Object>> refs = normalize(jdbc.queryForList("""
            SELECT ref_id AS "refId",owner_type AS "ownerType",owner_id AS "ownerId",
                   definition_type AS "definitionType",definition_code AS code,
                   definition_version AS version,release_ref AS "releaseRef",
                   effective_time AS "effectiveTime",definition_sha256 AS "contentSha256",
                   binding_method AS "bindingMethod",binding_confidence AS "bindingConfidence",
                   definition_name AS "definitionName",
                   definition_description AS "definitionDescription",
                   classification_path AS tactics
            FROM case_core_chain_knowledge_ref
            WHERE chain_id=? ORDER BY definition_type,definition_code,definition_version
            """, chainId), "tactics");
        return enrichKnowledgeRefs(refs);
    }

    /**
     * Resolve the frozen reference to business-readable knowledge metadata. The
     * reference remains owned by the case snapshot; names, definitions and the
     * AMLTRIX tactic path are copied into the snapshot when it is created.
     * Enrichment here also supplies a compatibility fallback for older snapshots.
     */
    private List<Map<String, Object>> enrichKnowledgeRefs(List<Map<String, Object>> refs) {
        for (Map<String, Object> ref : refs) {
            if (!Objects.toString(ref.get("definitionName"), "").isBlank()) continue;
            String type = text(ref, "definitionType");
            String code = text(ref, "code");
            String version = text(ref, "version");
            if (code.isBlank()) continue;
            List<Map<String, Object>> metadata = switch (type) {
                case "TECHNIQUE" -> normalize(jdbc.queryForList("""
                    SELECT t.term_name AS "definitionName",
                           t.definition AS "definitionDescription",
                           COALESCE((
                             SELECT jsonb_agg(jsonb_build_object(
                               'code',r.source_code,'version',r.source_version,
                               'name',tactic.term_name,'description',tactic.definition)
                               ORDER BY r.is_primary DESC,r.source_code)
                             FROM knowledge_asset_relation r
                             LEFT JOIN ontology_term tactic
                               ON tactic.namespace='AMLTRIX_TACTIC'
                              AND tactic.term_code=r.source_code
                              AND tactic.term_version::varchar=r.source_version
                             WHERE r.relation_type='TACTIC_HAS_TECHNIQUE'
                               AND r.target_type='TECHNIQUE'
                               AND r.target_code=t.term_code
                               AND r.status<>'RETIRED'
                           ),'[]'::jsonb) AS tactics
                    FROM ontology_term t
                    WHERE t.namespace='AMLTRIX_TECHNIQUE' AND t.term_code=?
                      AND t.status<>'RETIRED'
                    ORDER BY CASE WHEN t.term_version::varchar=? THEN 0 ELSE 1 END,
                             t.term_version DESC
                    LIMIT 1
                    """, code, version), "tactics");
                case "RISK_CATEGORY" -> jdbc.queryForList("""
                    SELECT risk_event_name AS "definitionName",
                           description AS "definitionDescription"
                    FROM risk_event_type_definition
                    WHERE risk_event_type=? AND status<>'RETIRED'
                    ORDER BY CASE WHEN version=? THEN 0 ELSE 1 END,version DESC
                    LIMIT 1
                    """, code, version);
                case "PATTERN" -> jdbc.queryForList("""
                    SELECT d.pattern_name AS "definitionName",
                           COALESCE(v.pattern_payload->>'description',
                                    v.pattern_payload->>'businessMeaning',
                                    d.pattern_name) AS "definitionDescription"
                    FROM event_pattern_definition d
                    LEFT JOIN event_pattern_version v
                      ON v.pattern_code=d.pattern_code AND v.version=?
                    WHERE d.pattern_code=? AND d.status<>'RETIRED'
                    LIMIT 1
                    """, version, code);
                case "INDICATOR" -> jdbc.queryForList("""
                    SELECT indicator_name AS "definitionName",
                           description AS "definitionDescription"
                    FROM indicator_definition
                    WHERE indicator_code=? AND status<>'RETIRED'
                    LIMIT 1
                    """, code);
                default -> List.of();
            };
            if (!metadata.isEmpty()) {
                Map<String, Object> resolved = metadata.get(0);
                ref.put("definitionName", resolved.get("definitionName"));
                ref.put("definitionDescription", resolved.get("definitionDescription"));
                ref.put("tactics", resolved.getOrDefault("tactics", List.of()));
            }
        }
        return refs;
    }

    private Map<String, Object> frozenObjectCounts(String chainId) {
        Map<String, Object> counts = jdbc.queryForMap("""
            SELECT
              COUNT(*) FILTER (WHERE canonical_type IN
                ('INDICATOR_RESULT','BEHAVIOR_PATTERN','RISK_HYPOTHESIS',
                 'INVESTIGATION_HYPOTHESIS')) AS reasoning_nodes,
              COUNT(*) FILTER (WHERE canonical_type NOT IN
                ('INDICATOR_RESULT','BEHAVIOR_PATTERN','RISK_HYPOTHESIS',
                 'INVESTIGATION_HYPOTHESIS')) AS event_nodes
            FROM case_core_chain_node WHERE chain_id=?
            """, chainId);
        Integer narratives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_core_chain_narrative_ref WHERE chain_id=?",
                Integer.class, chainId);
        Integer knowledge = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_core_chain_knowledge_ref WHERE chain_id=?",
                Integer.class, chainId);
        return props("eventGraphNodes", counts.get("event_nodes"),
                "reasoningGraphNodes", counts.get("reasoning_nodes"),
                "narrativeObjects", narratives, "knowledgeObjectsReferenced", knowledge,
                "auxiliaryRecords", 0);
    }

    private Map<String, Object> latestSnapshot(String caseId) {
        return jdbc.query("""
            SELECT chain_id,revision,projection_status
            FROM case_core_chain_snapshot WHERE case_id=?
            ORDER BY revision DESC LIMIT 1
            """, rs -> rs.next() ? props("chainId", rs.getString("chain_id"),
                    "revision", rs.getInt("revision"),
                    "projectionStatus", rs.getString("projection_status")) : Map.of(), caseId);
    }

    private List<String> risksSharingPatterns(Map<String, Object> technique,
                                              Map<String, Map<String, Object>> risks) {
        Set<String> patternRefs = new LinkedHashSet<>(refs(technique.get("behavior_occurrence_refs")));
        List<String> result = new ArrayList<>();
        risks.forEach((id, risk) -> {
            if (refs(risk.get("behavior_occurrence_refs")).stream().anyMatch(patternRefs::contains)) {
                result.add(id);
            }
        });
        return result;
    }

    private Map<String, Object> withCommon(Map<String, Object> source, String canonicalType,
                                            String epistemicType) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        String persistedBusinessId = Objects.toString(source.get("business_id"), "").trim();
        if (!persistedBusinessId.isBlank()) {
            result.putIfAbsent("businessId", persistedBusinessId);
        }
        result.put("canonicalType", canonicalType);
        result.put("epistemicType", epistemicType);
        return result;
    }

    private String patternInstanceName(Map<String, Object> pattern,
                                       List<String> eventRefs,
                                       List<Map<String, Object>> events) {
        String code = text(pattern, "pattern_code");
        String rawName = first(pattern, "pattern_name", "pattern_code", "occurrence_id");
        if ("TEXT_HIGH_FREQUENCY_PASS_THROUGH".equals(code)) {
            return "高频收付后快速转出的资金过渡行为";
        }
        if ("TEXT_ROUND_AMOUNT_STRUCTURING".equals(code)) {
            return "高频整数倍小额拆分交易行为";
        }
        if ("TEXT_MULTI_ACCOUNT_LAYERING".equals(code)) {
            return "多账户逐级归集与转移行为";
        }
        if ("TEXT_AMLTRIX_REPORTED_PATTERN".equals(code)
                || rawName.contains("AMLTRIX") || rawName.contains("文本陈述")) {
            Set<String> signals = eventSignals(eventRefs, events);
            if (signals.containsAll(Set.of("开户", "收款", "转账", "第三方支付"))) {
                return "开户后连续收付并涉及第三方支付的资金行为组合";
            }
            if (signals.contains("收款") && signals.contains("转账")) {
                return "多笔收付与资金转移行为组合";
            }
            return eventRefs.size() + "项具体事件形成的多阶段资金行为组合";
        }
        return rawName;
    }

    private String patternBehaviorMeaning(List<String> eventRefs,
                                          List<Map<String, Object>> events) {
        Set<String> signals = eventSignals(eventRefs, events);
        List<String> descriptions = new ArrayList<>();
        if (signals.contains("开户")) descriptions.add("账户开户");
        if (signals.contains("收款")) descriptions.add("多笔收款");
        if (signals.contains("转账")) descriptions.add("付款或转账");
        if (signals.contains("第三方支付")) descriptions.add("第三方支付");
        String observed = descriptions.isEmpty()
                ? eventRefs.size() + " 个具体事件"
                : String.join("、", descriptions);
        return "本案例中观察到" + observed
                + "前后衔接，形成可复核的资金活动组合；"
                + "该节点描述实际行为结构，不等同于洗钱风险定性。";
    }

    private Set<String> eventSignals(List<String> eventRefs,
                                     List<Map<String, Object>> events) {
        Set<String> signals = new LinkedHashSet<>();
        events.stream()
                .filter(event -> eventRefs.contains(text(event, "event_id")))
                .forEach(event -> {
                    String value = first(event, "event_name", "event_type");
                    String type = text(event, "event_type");
                    String combined = value + type;
                    if (combined.contains("开户")) signals.add("开户");
                    if (combined.contains("收款")) signals.add("收款");
                    if (combined.contains("付款") || combined.contains("转账")) signals.add("转账");
                    if (combined.contains("第三方支付")) signals.add("第三方支付");
                });
        return signals;
    }

    private String riskInstanceName(Map<String, Object> risk) {
        String type = text(risk, "risk_event_type");
        if ("REPORTED_COMPOSITE_LAUNDERING_SCENARIO".equals(type)) {
            return "多阶段资金转移与资产转换风险假设";
        }
        return first(risk, "title", "summary", "risk_event_type");
    }

    private String reasoningEventSummary(List<String> eventRefs,
                                         List<Map<String, Object>> events) {
        List<String> names = events.stream()
                .filter(event -> eventRefs.contains(text(event, "event_id")))
                .map(event -> first(event, "event_name", "event_type", "event_id"))
                .distinct()
                .limit(4)
                .toList();
        if (names.isEmpty()) return "当前实例未找到可展示的支持事件名称。";
        String suffix = eventRefs.size() > names.size()
                ? "等共 " + eventRefs.size() + " 个事件。" : "。";
        return "主要依据：" + String.join("、", names) + suffix;
    }

    private String percent(Object value) {
        if (value == null) return "未计算";
        try {
            return Math.round(Double.parseDouble(value.toString()) * 100) + "%";
        } catch (NumberFormatException ignored) {
            return value.toString();
        }
    }

    private String joinOrNone(List<String> values) {
        return values.isEmpty() ? "尚未明确" : String.join("、", values);
    }

    /**
     * Every vertex returned or frozen by the case graph is an instance.
     * Node-type definitions remain in graph_node_type_registry and are only
     * referenced by nodeType; they are never materialized as case vertices.
     */
    @SuppressWarnings("unchecked")
    private void stampInstanceContract(String caseId, List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            Object raw = node.get("properties");
            if (!(raw instanceof Map<?, ?>)) continue;
            Map<String, Object> properties = (Map<String, Object>) raw;
            String instanceId = Objects.toString(node.get("id"), "");
            String nodeType = Objects.toString(properties.get("canonicalType"), "");
            properties.put("instanceId", instanceId);
            properties.put("nodeType", nodeType);
            properties.put("caseId", caseId);
            properties.put("objectSemantics", "INSTANCE");
            properties.put("graphDomain", Set.of("INDICATOR_RESULT", "BEHAVIOR_PATTERN",
                    "RISK_HYPOTHESIS", "INVESTIGATION_HYPOTHESIS").contains(nodeType)
                    ? "REASONING_GRAPH" : "EVENT_GRAPH");
        }
    }

    void completeBusinessIds(String caseId, List<Map<String, Object>> nodes,
                             List<Map<String, Object>> edges) {
        Long caseSequenceId = jdbc.queryForObject(
                "SELECT id FROM cf_risk_case WHERE case_id=? AND deleted=false",
                Long.class, caseId);
        if (caseSequenceId == null) {
            throw new IllegalArgumentException("Case does not exist: " + caseId);
        }
        assignBusinessIds(caseSequenceId, nodes, edges);
    }

    @SuppressWarnings("unchecked")
    void assignBusinessIds(long caseSequenceId, List<Map<String, Object>> nodes,
                           List<Map<String, Object>> edges) {
        Map<String, Integer> sequences = new LinkedHashMap<>();
        Map<String, String> nodeBusinessIds = new LinkedHashMap<>();
        Set<String> usedBusinessIds = new LinkedHashSet<>();
        List<Map<String, Object>> orderedNodes = new ArrayList<>(nodes);
        orderedNodes.sort(Comparator
                .comparing(this::canonicalType)
                .thenComparing(node -> Objects.toString(node.get("id"), "")));

        // Stable IDs already persisted on domain objects (especially
        // cf_risk_event.business_id) are authoritative. Reserve them before
        // allocating IDs for explanation-only nodes.
        for (Map<String, Object> node : orderedNodes) {
            Map<String, Object> properties = (Map<String, Object>) node.get("properties");
            String existing = Objects.toString(properties.get("businessId"), "").trim();
            if (!existing.isBlank() && usedBusinessIds.add(existing)) {
                nodeBusinessIds.put(Objects.toString(node.get("id"), ""), existing);
                node.put("businessId", existing);
                advanceSequence(sequences, canonicalType(node), existing);
            } else if (!existing.isBlank()) {
                properties.remove("businessId");
            }
        }
        for (Map<String, Object> node : orderedNodes) {
            String canonical = canonicalType(node);
            String nodeId = Objects.toString(node.get("id"), "");
            String businessId = nodeBusinessIds.get(nodeId);
            if (businessId == null) {
                if ("CASE".equals(canonical)) {
                    businessId = Long.toString(caseSequenceId);
                } else {
                    do {
                        int sequence = sequences.merge(canonical, 1, Integer::sum);
                        businessId = caseSequenceId + "-" + businessIdPrefix(canonical) + "-"
                                + String.format("%03d", sequence);
                    } while (usedBusinessIds.contains(businessId));
                }
                usedBusinessIds.add(businessId);
            }
            Map<String, Object> properties = (Map<String, Object>) node.get("properties");
            properties.put("businessId", businessId);
            node.put("businessId", businessId);
            nodeBusinessIds.put(nodeId, businessId);
        }
        List<Map<String, Object>> orderedEdges = new ArrayList<>(edges);
        orderedEdges.sort(Comparator
                .comparing((Map<String, Object> edge) ->
                        Objects.toString(edge.get("source"), ""))
                .thenComparing(edge -> Objects.toString(edge.get("type"), ""))
                .thenComparing(edge -> Objects.toString(edge.get("target"), ""))
                .thenComparing(edge -> Objects.toString(edge.get("id"), "")));
        int sequence = 0;
        for (Map<String, Object> edge : orderedEdges) {
            Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
            String existing = Objects.toString(properties.get("businessId"), "").trim();
            if (!existing.isBlank() && usedBusinessIds.add(existing)) {
                edge.put("businessId", existing);
                sequence = Math.max(sequence, trailingSequence(existing));
            } else if (!existing.isBlank()) {
                properties.remove("businessId");
            }
        }
        for (Map<String, Object> edge : orderedEdges) {
            String source = Objects.toString(edge.get("source"), "");
            String target = Objects.toString(edge.get("target"), "");
            Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
            String businessId = Objects.toString(properties.get("businessId"), "").trim();
            if (businessId.isBlank()) {
                do {
                    businessId = caseSequenceId + "-REL-" + String.format("%03d", ++sequence);
                } while (usedBusinessIds.contains(businessId));
                usedBusinessIds.add(businessId);
            }
            properties.put("businessId", businessId);
            edge.put("businessId", businessId);
            properties.put("sourceBusinessId", nodeBusinessIds.get(source));
            properties.put("targetBusinessId", nodeBusinessIds.get(target));
        }
    }

    private void advanceSequence(Map<String, Integer> sequences, String canonicalType,
                                 String businessId) {
        int persistedSequence = trailingSequence(businessId);
        if (persistedSequence > 0) {
            sequences.merge(canonicalType, persistedSequence, Math::max);
        }
    }

    private int trailingSequence(String businessId) {
        int separator = businessId.lastIndexOf('-');
        if (separator < 0 || separator == businessId.length() - 1) return 0;
        try {
            return Integer.parseInt(businessId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String businessIdPrefix(String canonicalType) {
        return switch (canonicalType) {
            case "CUSTOMER" -> "CUS";
            case "ORGANIZATION" -> "ORG";
            case "MERCHANT" -> "MCH";
            case "ACCOUNT" -> "ACC";
            case "WALLET" -> "WAL";
            case "DEVICE" -> "DEV";
            case "IP_ADDRESS", "IPADDRESS" -> "IPA";
            case "ADDRESS" -> "ADR";
            case "EVIDENCE" -> "EVD";
            case "EVENT" -> "EVT";
            case "INDICATOR_RESULT" -> "IND";
            case "BEHAVIOR_PATTERN" -> "PAT";
            case "RISK_HYPOTHESIS" -> "RSK";
            case "INVESTIGATION_HYPOTHESIS" -> "INV";
            default -> "OBJ";
        };
    }

    /**
     * Makes graph_relation_type_registry executable.  Builders only choose a
     * semantic relation code; endpoint compatibility, Chinese display name,
     * graph domain and edge category come from the registry.
     */
    @SuppressWarnings("unchecked")
    private void applyRelationContracts(List<Map<String, Object>> nodes,
                                        List<Map<String, Object>> edges,
                                        List<Map<String, Object>> issues) {
        List<Map<String, Object>> rows = normalize(jdbc.queryForList("""
            SELECT r.relation_type,r.display_name,r.graph_domain,r.edge_category,
                   allowed_source_types,allowed_target_types,
                   participates_in_reasoning,requires_evidence,
                   semantic_level,direction_semantics,required_edge_properties,
                   m.option_label AS framework_category
            FROM graph_relation_type_registry r
            LEFT JOIN case_framework_option_metadata m
              ON m.field_code='relationType'
             AND m.option_code=r.framework_category_code AND m.status='ACTIVE'
            WHERE r.schema_version='1.8' AND r.status='ACTIVE'
            """), "allowed_source_types", "allowed_target_types", "required_edge_properties");
        Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
        rows.forEach(row -> contracts.put(text(row, "relation_type"), row));
        Map<String, String> nodeTypes = new LinkedHashMap<>();
        nodes.forEach(node -> nodeTypes.put(Objects.toString(node.get("id"), ""),
                canonicalType(node)));

        edges.removeIf(edge -> {
            String relation = Objects.toString(edge.get("type"), "");
            Map<String, Object> contract = contracts.get(relation);
            if (contract == null) {
                issues.add(issue("CC_REL_001", "ERROR",
                        Objects.toString(edge.get("id"), ""),
                        "关系类型 " + relation + " 未在关系注册表中启用，已剔除。"));
                return true;
            }
            String sourceType = nodeTypes.getOrDefault(
                    Objects.toString(edge.get("source"), ""), "");
            String targetType = nodeTypes.getOrDefault(
                    Objects.toString(edge.get("target"), ""), "");
            Set<String> allowedSources =
                    new LinkedHashSet<>(refs(contract.get("allowed_source_types")));
            Set<String> allowedTargets =
                    new LinkedHashSet<>(refs(contract.get("allowed_target_types")));
            if (!allowedSources.contains(sourceType) || !allowedTargets.contains(targetType)) {
                issues.add(issue("CC_REL_002", "ERROR",
                        Objects.toString(edge.get("id"), ""),
                        relation + " 不允许 " + sourceType + " → " + targetType
                                + "，已按关系契约剔除。"));
                return true;
            }
            Map<String, Object> properties =
                    (Map<String, Object>) edge.get("properties");
            properties.put("sourceType", sourceType);
            properties.put("targetType", targetType);
            properties.put("relationName", contract.get("display_name"));
            properties.put("relationCategory", contract.get("framework_category"));
            properties.put("relationDomain", contract.get("graph_domain"));
            properties.put("edgeCategory", contract.get("edge_category"));
            properties.put("semanticLevel", contract.get("semantic_level"));
            properties.put("directionSemantics", contract.get("direction_semantics"));
            properties.put("participatesInReasoning",
                    contract.get("participates_in_reasoning"));
            properties.put("requiresEvidence", contract.get("requires_evidence"));
            return false;
        });
    }

    private void addNode(List<Map<String, Object>> nodes, Set<String> ids, String id,
                         String legacyType, String canonicalType, String plane,
                         String name, Map<String, Object> properties) {
        if (id.isBlank() || !ids.add(id)) return;
        properties.put("canonicalType", canonicalType);
        properties.put("graphPlane", plane);
        nodes.add(props("id", id, "uid", id, "label", legacyType, "name", name,
                "properties", properties));
    }

    private void addEdge(List<Map<String, Object>> edges, String source, String sourceType,
                         String relation, String target, String targetType, String category,
                         String pathRole, String origin, boolean legacyDerived) {
        if (source.isBlank() || target.isBlank()) return;
        String id = "EDGE-" + sha256(source + "|" + relation + "|" + target).substring(0, 20);
        edges.add(props("id", id, "uid", id, "source", source, "target", target,
                "type", relation, "properties", props("edgeCategory", category,
                        "pathRole", pathRole, "edgeOrigin", origin, "sourceType", sourceType,
                        "targetType", targetType, "legacyDerived", legacyDerived)));
    }

    private Map<String, Object> issue(String code, String severity, String nodeKey, String message) {
        return props("code", code, "severity", severity, "nodeKey", nodeKey, "message", message);
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows, String... fields) {
        rows.forEach(row -> {
            for (String field : fields) {
                Object value = row.get(field);
                if (value instanceof PGobject pg) {
                    try { row.put(field, mapper.readTree(pg.getValue())); }
                    catch (Exception ignored) { row.put(field, List.of()); }
                } else if (value instanceof String text && (text.startsWith("[") || text.startsWith("{"))) {
                    try { row.put(field, mapper.readTree(text)); }
                    catch (Exception ignored) { /* retain diagnostic value */ }
                }
            }
        });
        return rows;
    }

    private List<String> refs(Object value) {
        JsonNode node;
        try {
            if (value instanceof JsonNode jsonNode) node = jsonNode;
            else if (value instanceof PGobject pg) node = mapper.readTree(pg.getValue());
            else node = mapper.valueToTree(value == null ? List.of() : value);
        } catch (Exception ex) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> {
            if (!item.asText().isBlank()) result.add(item.asText());
        });
        return result;
    }

    private List<String> scopedRefs(String caseId, List<String> refs) {
        return refs.stream().map(ref -> scoped(caseId, ref)).toList();
    }

    private void addTypedRefs(List<Map<String, String>> target, String type, List<String> refs) {
        refs.forEach(ref -> target.add(Map.of("type", type, "id", ref)));
    }

    private String scoped(String caseId, String id) {
        return id.startsWith(caseId + "::") ? id : caseId + "::" + id;
    }

    private String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private String first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = text(row, key);
            if (!value.isBlank()) return value;
        }
        return "未命名节点";
    }

    private Map<String, Object> props(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(Objects.toString(values[i]), values[i + 1]);
        }
        return result;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid core-chain JSON", ex); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
