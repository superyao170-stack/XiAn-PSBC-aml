package com.datagraph.bank.service;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TuGraphStructuredWriter {
    // V1.8: knowledge/facet/binding/narrative objects stay outside TuGraph.
    private static final boolean PROJECT_AUXILIARY_OBJECTS = false;
    private final JdbcTemplate jdbc;
    private final String uri;
    private final String username;
    private final String password;
    private final Driver structuredDriver;
    private final ContractProjectionService contractProjectionService;
    private final EventSemanticEnrichmentService eventSemanticEnrichmentService;
    private final AtomicBoolean frameworkSchemaReady = new AtomicBoolean(false);

    public TuGraphStructuredWriter(JdbcTemplate jdbc, ContractProjectionService contractProjectionService,
            EventSemanticEnrichmentService eventSemanticEnrichmentService,
            @Value("${graph.tugraph.uri:bolt://localhost:7687}") String uri,
            @Value("${graph.tugraph.username:admin}") String username,
            @Value("${graph.tugraph.password:tugraph@123}") String password) {
        this.jdbc = jdbc; this.contractProjectionService = contractProjectionService;
        this.eventSemanticEnrichmentService = eventSemanticEnrichmentService;
        this.uri = uri; this.username = username; this.password = password;
        this.structuredDriver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @PreDestroy
    public void closeStructuredDriver() {
        structuredDriver.close();
    }

    public Map<String,Object> writeCase(String caseId, String bankCode, Long workspaceId) {
        eventSemanticEnrichmentService.enrichCase(caseId);
        String transactionSql = """
            SELECT t.id, s.signal_id, s.algorithm_id, s.algorithm_version,
                   t.source_record_id, t.account_hash, t.counterparty_hash,
                   t.amount, t.currency, t.occurred_at
            FROM case_signal_rel r JOIN risk_signal s ON s.signal_id=r.signal_id
            JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
            WHERE r.case_id=? AND t.bank_code=? %s
            ORDER BY t.id""".formatted(workspaceId == null ? "" : "AND t.workspace_id=?");
        List<Map<String,Object>> txs = workspaceId == null
                ? jdbc.queryForList(transactionSql, caseId, bankCode)
                : jdbc.queryForList(transactionSql, caseId, bankCode, workspaceId);
        String caseSource = jdbc.queryForObject(
                "SELECT case_source FROM cf_risk_case WHERE case_id=? AND deleted=false",
                String.class, caseId);
        boolean workerDatasetCase = "STRUCT_SUSPECTED".equalsIgnoreCase(caseSource);
        // The graph writer is a protected boundary: no case reaches TuGraph
        // before its current relational projection passes the active contract.
        contractProjectionService.captureCase(caseId);
        try (var admin = structuredDriver.session()) {
            try {
                admin.run("CALL dbms.graph.createGraph('BankGraph','bankgraph structured AML graph',1024)").consume();
            } catch (Exception ignored) { /* graph already exists */ }
        }
        try (var session = structuredDriver.session(SessionConfig.forDatabase("BankGraph"))) {
            for (String label : List.of("Account", "Transaction", "Case", "Evidence", "Event", "Customer")) {
                try {
                    session.run("CALL db.createVertexLabel('" + label + "','graphId','graphId','string',false,'bankCode','string',true,'accountHash','string',true,'sourceRecordId','string',true,'amount','string',true,'currency','string',true,'occurredAt','string',true,'nodeType','string',true,'caseId','string',true,'workspaceId','string',true,'summary','string',true,'eventName','string',true,'eventType','string',true,'eventText','string',true)").consume();
                } catch (Exception ignored) { }
            }
            for (String label : List.of("TRANSFER", "INVOLVES", "HAS_TRANSACTION", "CONTAINS_EVIDENCE", "CONTAINS_EVENT", "SUPPORTS_EVENT", "OWNS_ACCOUNT")) {
                try {
                    session.run("CALL db.createEdgeLabel('" + label + "','[]','graphId','string',true)").consume();
                } catch (Exception ignored) { }
            }
            // Probe the Case primary key before cleanup. New cases have nothing
            // to delete; scanning every label by caseId made each insert O(N).
            boolean rebuilding = session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId)
                    + "'}) RETURN c LIMIT 1").hasNext();
            if (rebuilding) {
                session.run("MATCH (n:Event {caseId:'" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
                session.run("MATCH (n:Evidence {caseId:'" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
                session.run("MATCH (n:Account {caseId:'" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
                session.run("MATCH (n:Customer {caseId:'" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
                session.run("MATCH (n:Transaction {caseId:'" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
                session.run("MATCH (n:Account) WHERE n.graphId STARTS WITH 'ACC-" + cypherLiteral(caseId) + "-' DETACH DELETE n").consume();
                session.run("MATCH (n:Transaction) WHERE n.graphId STARTS WITH 'TX-" + cypherLiteral(caseId) + "-' DETACH DELETE n").consume();
                session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId) + "'})-[r]-() DELETE r").consume();
            }
            session.run("MERGE (c:Case {graphId:'" + cypherLiteral(caseId) + "'}) "
                    + "SET c.caseId='" + cypherLiteral(caseId) + "',"
                    + "c.bankCode='" + cypherLiteral(bankCode) + "',"
                    + "c.workspaceId='" + cypherLiteral(Objects.toString(workspaceId, "")) + "',c.nodeType='CASE'").consume();
            List<Map<String,Object>> events = jdbc.queryForList(
                    "SELECT event_id,event_name FROM cf_risk_event WHERE case_id=? AND deleted=false ORDER BY event_time,event_id",
                    caseId);
            List<Map<String,Object>> eventRows = events.stream().map(event -> Map.<String,Object>of(
                    "eventId", Objects.toString(event.get("event_id")),
                    "caseId", caseId,
                    "bankCode", bankCode)).toList();
            for (Map<String,Object> row : eventRows) {
                String eventId = Objects.toString(row.get("eventId"));
                session.run("MERGE (v:Event {graphId:'" + cypherLiteral(eventId)
                        + "'}) SET v.caseId='" + cypherLiteral(caseId) + "',v.bankCode='"
                        + cypherLiteral(bankCode) + "',v.nodeType='EVENT'").consume();
                session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId)
                        + "'}),(v:Event {graphId:'" + cypherLiteral(eventId)
                        + "'}) MERGE (c)-[:CONTAINS_EVENT]->(v)").consume();
            }
            Set<String> evidenceIds = new HashSet<>();
            if (workerDatasetCase) {
                String reportEvidenceId = "EVID-" + caseId;
                evidenceIds.add(reportEvidenceId);
                session.run("MERGE (e:Evidence {graphId:'" + cypherLiteral(reportEvidenceId)
                        + "'}) SET e.caseId='" + cypherLiteral(caseId) + "',e.bankCode='"
                        + cypherLiteral(bankCode) + "',e.nodeType='EVIDENCE'").consume();
                session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId)
                        + "'}),(e:Evidence {graphId:'" + cypherLiteral(reportEvidenceId)
                        + "'}) MERGE (c)-[:CONTAINS_EVIDENCE]->(e)").consume();
                for (Map<String,Object> event : events) {
                    session.run("MATCH (e:Evidence {graphId:'" + cypherLiteral(reportEvidenceId)
                            + "'}),(v:Event {graphId:'" + cypherLiteral(Objects.toString(event.get("event_id")))
                            + "'}) MERGE (e)-[:SUPPORTS_EVENT]->(v)").consume();
                }
            }
            // A clustered event can represent multiple suspicious transactions.
            // Attach every source/counterparty account to its assigned event;
            // transaction rows remain evidence inputs rather than graph nodes.
            List<Map<String,Object>> accountLinks = new ArrayList<>();
            // Keep the graph event-centred and bounded for interactive visualization.
            // The complete case-to-signal lineage remains in PostgreSQL; TuGraph is a
            // representative projection and must not issue tens of thousands of serial
            // writes for a single large worker-generated component.
            int representativeCount = events.isEmpty() ? 0 : Math.min(txs.size(),
                    200);
            for (int txIndex = 0; txIndex < representativeCount; txIndex++) {
                Map<String,Object> tx = txs.get(txIndex);
                String eventId = Objects.toString(events.get(txIndex % events.size()).get("event_id"));
                String signalId = Objects.toString(tx.get("signal_id"), "");
                if (!workerDatasetCase && !signalId.isBlank()) {
                    String evidenceId = "EVID-SIGNAL-" + signalId;
                    evidenceIds.add(evidenceId);
                    session.run("MERGE (e:Evidence {graphId:'" + cypherLiteral(evidenceId)
                            + "'}) SET e.caseId='" + cypherLiteral(caseId) + "',e.bankCode='"
                            + cypherLiteral(bankCode) + "',e.nodeType='EVIDENCE'").consume();
                    session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId)
                            + "'}),(e:Evidence {graphId:'" + cypherLiteral(evidenceId)
                            + "'}) MERGE (c)-[:CONTAINS_EVIDENCE]->(e)").consume();
                    session.run("MATCH (e:Evidence {graphId:'" + cypherLiteral(evidenceId)
                            + "'}),(v:Event {graphId:'" + cypherLiteral(eventId)
                            + "'}) MERGE (e)-[:SUPPORTS_EVENT]->(v)").consume();
                }
                for (String hash : List.of(
                        Objects.toString(tx.get("account_hash"), ""),
                        Objects.toString(tx.get("counterparty_hash"), ""))) {
                    if (hash.isBlank()) continue;
                    String accountId = "ACC-" + caseId + "-" + hash;
                    accountLinks.add(Map.of("eventId", eventId, "accountId", accountId,
                            "caseId", caseId, "bankCode", bankCode, "accountHash", hash));
                }
            }
            Set<String> writtenAccountIds = new HashSet<>();
            Set<String> writtenCustomerIds = new HashSet<>();
            for (Map<String,Object> row : accountLinks) {
                String eventId = Objects.toString(row.get("eventId"));
                String accountId = Objects.toString(row.get("accountId"));
                String accountHash = Objects.toString(row.get("accountHash"));
                session.run("MERGE (a:Account {graphId:'" + cypherLiteral(accountId)
                        + "'}) SET a.caseId='" + cypherLiteral(caseId) + "',a.bankCode='"
                        + cypherLiteral(bankCode) + "',a.accountHash='" + cypherLiteral(accountHash)
                        + "',a.nodeType='ACCOUNT'").consume();
                session.run("MATCH (v:Event {graphId:'" + cypherLiteral(eventId)
                        + "'}),(a:Account {graphId:'" + cypherLiteral(accountId)
                        + "'}) MERGE (v)-[:INVOLVES]->(a)").consume();
                writtenAccountIds.add(accountId);
                if (workerDatasetCase) {
                    createCustomerAccountOwnership(session, caseId, bankCode, accountHash, accountId);
                    writtenCustomerIds.add("CUST-" + caseId + "-" + accountHash);
                }
            }
            return Map.of("engine","TUGRAPH","sourceTransactions",txs.size(),
                    "events",events.size(),"evidences",evidenceIds.size(),
                    "accounts",writtenAccountIds.size(),"customers",writtenCustomerIds.size(),
                    "graphRepresentativeTransactions", representativeCount,
                    "graphProjectionTruncated", representativeCount < txs.size());
        }
    }

    public Map<String,Object> rebuildCase(String caseId) {
        Map<String,Object> row = jdbc.queryForMap(
                "SELECT bank_code,workspace_id FROM cf_risk_case WHERE case_id=? AND deleted=false", caseId);
        long workspaceId = row.get("workspace_id") instanceof Number value ? value.longValue() : 0L;
        List<String> storedFrameworks = jdbc.queryForList("""
                SELECT framework_result::text FROM case_processing_pool
                WHERE case_id=? AND framework_result IS NOT NULL
                """, String.class, caseId);
        Map<String,Object> facts;
        if (!storedFrameworks.isEmpty()) {
            try {
                JsonNode framework = new ObjectMapper().readTree(storedFrameworks.get(0));
                facts = writeFrameworkCase(caseId, Objects.toString(row.get("bank_code")),
                        workspaceId, framework);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("案例框架数据无法解析: " + caseId, ex);
            }
        } else {
            facts = writeCase(caseId, Objects.toString(row.get("bank_code")),
                    workspaceId == 0L ? null : workspaceId);
        }
        Map<String,Object> result = new LinkedHashMap<>(facts);
        result.put("reasoning", writeReasoningGraph(caseId));
        return result;
    }

    public Map<String,Object> projectCase(String caseId) {
        String source = jdbc.queryForObject(
                "SELECT case_source FROM cf_risk_case WHERE case_id=? AND deleted=false",
                String.class, caseId);
        return "TEXT_CASE".equalsIgnoreCase(source) ? rebuildTextCase(caseId) : rebuildCase(caseId);
    }

    public Map<String,Object> writeReasoningGraph(String caseId) {
        Map<String,Object> caseRow = jdbc.queryForMap("""
            SELECT bank_code,workspace_id,case_source
            FROM cf_risk_case WHERE case_id=? AND deleted=false
            """, caseId);
        String bankCode = Objects.toString(caseRow.get("bank_code"), "");
        String workspaceId = Objects.toString(caseRow.get("workspace_id"), "1");
        boolean textCase = "TEXT_CASE".equalsIgnoreCase(
                Objects.toString(caseRow.get("case_source"), ""));
        List<Map<String,Object>> behaviors = jdbc.queryForList("""
            SELECT occurrence_id,pattern_code,pattern_version,pattern_class,
                   pattern_confidence,evidence_strength,status
            FROM behavior_pattern_occurrence WHERE case_id=? AND status='ACTIVE'
            """, caseId);
        List<Map<String,Object>> risks = jdbc.queryForList("""
            SELECT risk_event_id,risk_event_type,title,summary,risk_confidence,
                   evidence_strength,risk_event_type_version,risk_event_definition_ref,status
            FROM risk_event_hypothesis WHERE case_id=? AND status='ACTIVE'
            """, caseId);
        List<Map<String,Object>> alternatives = jdbc.queryForList("""
            SELECT explanation_id,alternative_type,title,summary,target_risk_event_id,
                   confidence,status
            FROM alternative_explanation WHERE case_id=? AND status='ACTIVE'
            """, caseId);
        List<Map<String,Object>> techniques = jdbc.queryForList("""
            SELECT occurrence_id,technique_code,technique_version,explanation,
                   mapping_confidence,risk_confidence,evidence_strength,o.status,
                   COALESCE(t.term_name,o.technique_code) AS technique_name
            FROM technique_occurrence o
            LEFT JOIN ontology_term t
              ON t.namespace='AMLTRIX_TECHNIQUE' AND t.term_code=o.technique_code
             AND t.term_version::varchar=o.technique_version AND t.status='ACTIVE'
            WHERE o.case_id=? AND o.status<>'SUPERSEDED'
            """, caseId);
        List<Map<String,Object>> matters = jdbc.queryForList("""
            SELECT matter_id,matter_type,summary,certainty,risk_event_refs,status
            FROM case_matter_explanation WHERE case_id=? AND status='ACTIVE'
            """, caseId);
        List<Map<String,Object>> investigations = jdbc.queryForList("""
            SELECT hypothesis_id,risk_event_id,hypothesis,priority,status
            FROM investigation_hypothesis WHERE case_id=?
            """, caseId);
        List<Map<String,Object>> indicators = jdbc.queryForList("""
            WITH refs AS (
              SELECT jsonb_array_elements_text(indicator_result_refs) calculation_id
              FROM case_matter_explanation WHERE case_id=? AND status='ACTIVE'
              UNION
              SELECT jsonb_array_elements_text(indicator_result_refs) calculation_id
              FROM technique_occurrence WHERE case_id=? AND status<>'SUPERSEDED'
            )
            SELECT c.calculation_id,c.indicator_code,c.indicator_version,c.numeric_value,
                   c.risk_level,d.indicator_name
            FROM refs r
            JOIN indicator_calculation_result c ON c.calculation_id=r.calculation_id
            JOIN indicator_definition d ON d.indicator_code=c.indicator_code
            ORDER BY c.indicator_code,c.calculation_id
            """, caseId, caseId);
        List<Map<String,Object>> semanticEvents = jdbc.queryForList("""
            SELECT e.event_id,e.semantic_profile_code,e.semantic_profile_version,
                   e.lifecycle_code,e.lifecycle_version,e.lifecycle_state,e.fact_level,
                   e.event_quality_score,e.canonical_event_id,e.identity_resolution_status,
                   p.profile_name,l.lifecycle_name
            FROM cf_risk_event e
            LEFT JOIN event_semantic_profile p
              ON p.profile_code=e.semantic_profile_code
             AND p.profile_version=e.semantic_profile_version
            LEFT JOIN event_lifecycle_definition l
              ON l.lifecycle_code=e.lifecycle_code
             AND l.lifecycle_version=e.lifecycle_version
            WHERE e.case_id=? AND e.deleted=false
            ORDER BY e.event_time,e.event_id
            """, caseId);

        try (var session = structuredDriver.session(SessionConfig.forDatabase("BankGraph"))) {
            for (String label : List.of("BehaviorPatternOccurrence", "RiskEvent",
                    "AlternativeExplanation", "TechniqueOccurrence", "Matter",
                    "InvestigationHypothesis", "BehaviorPatternDefinition", "Technique",
                     "IndicatorResult", "IndicatorDefinition", "RiskEventTypeDefinition", "Tactic",
                    "EventSemanticProfile", "EventLifecycle", "EventLifecycleState",
                    "CanonicalEvent")) {
                try {
                    session.run("CALL db.createVertexLabel('" + label
                            + "','graphId','graphId','string',false,"
                            + "'caseId','string',true,'bankCode','string',true,"
                            + "'workspaceId','string',true,'nodeType','string',true,"
                            + "'graphPlane','string',true,'code','string',true,"
                            + "'version','string',true,'summary','string',true,"
                            + "'status','string',true,'confidence','string',true,"
                            + "'evidenceStrength','string',true)").consume();
                } catch (Exception ignored) { /* label already exists */ }
            }
            for (String label : List.of("CONTAINS_BEHAVIOR_PATTERN", "MATCHES_BEHAVIOR_PATTERN",
                    "INSTANCE_OF_PATTERN", "SUPPORTS_RISK_EVENT",
                    "SUPPORTS_RISK_HYPOTHESIS", "ALTERNATIVE_TO",
                    "CONTAINS_TECHNIQUE_OCCURRENCE", "INTERPRETED_AS", "INSTANCE_OF_TECHNIQUE",
                    "CONTAINS_MATTER", "SUMMARIZES", "RAISES_HYPOTHESIS",
                    "CONTAINS_INVESTIGATION_HYPOTHESIS", "CONTAINS_INDICATOR_RESULT",
                     "INSTANCE_OF_INDICATOR", "SUPPORTS_BEHAVIOR_PATTERN",
                     "INSTANCE_OF_RISK_EVENT_TYPE",
                    "TECHNIQUE_SERVES_TACTIC", "REFERENCES_EVENT_PROFILE",
                    "INSTANCE_OF_EVENT_PROFILE", "GOVERNED_BY_LIFECYCLE",
                    "CURRENT_LIFECYCLE_STATE", "RESOLVES_TO_CANONICAL_EVENT",
                    "CONTAINS_CANONICAL_EVENT", "PRECEDES", "FUNDS_FLOW_TO",
                    "SAME_SESSION_NEXT")) {
                try {
                    session.run("CALL db.createEdgeLabel('" + label + "','[]')").consume();
                } catch (Exception ignored) { /* label already exists */ }
            }
            for (String label : List.of("BehaviorPatternOccurrence", "RiskEvent",
                    "AlternativeExplanation", "TechniqueOccurrence", "Matter",
                    "InvestigationHypothesis", "IndicatorResult", "CanonicalEvent")) {
                session.run("MATCH (n:" + label + " {caseId:'" + cypherLiteral(caseId)
                        + "'}) DETACH DELETE n").consume();
            }
            session.run("MATCH (n {caseId:'" + cypherLiteral(caseId)
                    + "'})-[r]-(k) WHERE k.graphPlane='KNOWLEDGE' DELETE r").consume();
            session.run("MERGE (c:Case {graphId:'" + cypherLiteral(caseId)
                    + "'}) SET c.caseId='" + cypherLiteral(caseId) + "',c.bankCode='"
                    + cypherLiteral(bankCode) + "',c.workspaceId='" + cypherLiteral(workspaceId)
                    + "',c.nodeType='CASE'").consume();

            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : semanticEvents) {
                String eventId = Objects.toString(row.get("event_id"));
                String profileCode = Objects.toString(row.get("semantic_profile_code"), "");
                String profileVersion = Objects.toString(row.get("semantic_profile_version"), "");
                if (!profileCode.isBlank()) {
                    String profileId = "EVENT_PROFILE:" + profileCode + ":v" + profileVersion;
                    mergeReasoningNode(session, "EventSemanticProfile", profileId, "", "", "",
                            "EVENT_SEMANTIC_PROFILE", "KNOWLEDGE", profileCode, profileVersion,
                            Objects.toString(row.get("profile_name"), profileCode), "ACTIVE", "", "");
                    mergeEdge(session, caseId, profileId, "REFERENCES_EVENT_PROFILE");
                    mergeEdge(session, eventId, profileId, "INSTANCE_OF_EVENT_PROFILE");
                }
                String lifecycleCode = Objects.toString(row.get("lifecycle_code"), "");
                String lifecycleVersion = Objects.toString(row.get("lifecycle_version"), "");
                if (!lifecycleCode.isBlank()) {
                    String lifecycleId = "EVENT_LIFECYCLE:" + lifecycleCode + ":v" + lifecycleVersion;
                    mergeReasoningNode(session, "EventLifecycle", lifecycleId, "", "", "",
                            "EVENT_LIFECYCLE", "KNOWLEDGE", lifecycleCode, lifecycleVersion,
                            Objects.toString(row.get("lifecycle_name"), lifecycleCode), "ACTIVE", "", "");
                    if (!profileCode.isBlank()) mergeEdge(session,
                            "EVENT_PROFILE:" + profileCode + ":v" + profileVersion,
                            lifecycleId, "GOVERNED_BY_LIFECYCLE");
                    String state = Objects.toString(row.get("lifecycle_state"), "");
                    if (!state.isBlank()) {
                        String stateId = lifecycleId + ":STATE:" + state;
                        mergeReasoningNode(session, "EventLifecycleState", stateId, "", "", "",
                                "EVENT_LIFECYCLE_STATE", "KNOWLEDGE", state, lifecycleVersion,
                                state + " / " + Objects.toString(row.get("fact_level"), "ASSERTION"),
                                "ACTIVE", "", "");
                        mergeEdge(session, eventId, stateId, "CURRENT_LIFECYCLE_STATE");
                    }
                }
                String canonicalId = Objects.toString(row.get("canonical_event_id"), "");
                if (!canonicalId.isBlank()) {
                    mergeReasoningNode(session, "CanonicalEvent", canonicalId, caseId, bankCode,
                            workspaceId, "CANONICAL_EVENT", "CORE", canonicalId, "1",
                            "规范事件身份", Objects.toString(row.get("identity_resolution_status"), "DISTINCT"),
                            Objects.toString(row.get("event_quality_score"), ""), "");
                    mergeEdge(session, eventId, canonicalId, "RESOLVES_TO_CANONICAL_EVENT");
                    mergeEdge(session, caseId, canonicalId, "CONTAINS_CANONICAL_EVENT");
                    if (!profileCode.isBlank()) mergeEdge(session, canonicalId,
                            "EVENT_PROFILE:" + profileCode + ":v" + profileVersion,
                            "INSTANCE_OF_EVENT_PROFILE");
                }
            }

            for (Map<String,Object> row : behaviors) {
                String occurrenceId = Objects.toString(row.get("occurrence_id"));
                String graphId = caseId + "::" + occurrenceId;
                mergeReasoningNode(session, "BehaviorPatternOccurrence", graphId, caseId, bankCode,
                        workspaceId, "BEHAVIOR_PATTERN", "REASONING",
                        Objects.toString(row.get("pattern_code")), Objects.toString(row.get("pattern_version")),
                        Objects.toString(row.get("pattern_class")) + " 行为模式实例",
                        Objects.toString(row.get("status")),
                        Objects.toString(row.get("pattern_confidence"), ""),
                        Objects.toString(row.get("evidence_strength"), ""));
                mergeEdge(session, caseId, graphId, "CONTAINS_BEHAVIOR_PATTERN");
                List<String> eventIds = jdbc.queryForList("""
                    SELECT jsonb_array_elements_text(event_refs)
                    FROM behavior_pattern_occurrence WHERE occurrence_id=?
                    """, String.class, occurrenceId);
                for (String eventId : eventIds) mergeEdge(session, eventId, graphId,
                        "MATCHES_BEHAVIOR_PATTERN");
                if (textCase) {
                    // The text extraction graph uses case-scoped Worker UIDs while the
                    // reasoning store references relational event IDs. Until both
                    // contracts share one physical graph key, case ownership is the
                    // audited fallback for connecting the extracted base events.
                    session.run("MATCH (v:Event {caseId:'" + cypherLiteral(caseId)
                            + "'}),(b:BehaviorPatternOccurrence {graphId:'"
                            + cypherLiteral(graphId)
                            + "'}) MERGE (v)-[:MATCHES_BEHAVIOR_PATTERN]->(b)").consume();
                }
                if (PROJECT_AUXILIARY_OBJECTS) {
                String patternGraphId = "PATTERN:" + Objects.toString(row.get("pattern_code"))
                        + ":v" + Objects.toString(row.get("pattern_version"));
                mergeReasoningNode(session, "BehaviorPatternDefinition", patternGraphId, "",
                        "", "", "BEHAVIOR_PATTERN_DEFINITION", "KNOWLEDGE",
                        Objects.toString(row.get("pattern_code")),
                        Objects.toString(row.get("pattern_version")),
                        Objects.toString(row.get("pattern_class")) + " 模式定义",
                        "ACTIVE", "", "");
                mergeEdge(session, graphId, patternGraphId, "INSTANCE_OF_PATTERN");
                }
            }

            for (Map<String,Object> row : risks) {
                String riskId = Objects.toString(row.get("risk_event_id"));
                String graphId = caseId + "::" + riskId;
                mergeReasoningNode(session, "RiskEvent", graphId, caseId, bankCode, workspaceId,
                        "RISK_HYPOTHESIS", "REASONING", Objects.toString(row.get("risk_event_type")),
                        "1", Objects.toString(row.get("title"), Objects.toString(row.get("summary"))),
                        Objects.toString(row.get("status")),
                        Objects.toString(row.get("risk_confidence"), ""),
                        Objects.toString(row.get("evidence_strength"), ""));
                List<String> behaviorIds = jdbc.queryForList("""
                    SELECT jsonb_array_elements_text(behavior_occurrence_refs)
                    FROM risk_event_hypothesis WHERE risk_event_id=?
                    """, String.class, riskId);
                for (String behaviorId : behaviorIds) mergeEdge(session,
                        caseId + "::" + behaviorId, graphId, "SUPPORTS_RISK_HYPOTHESIS");
                String riskVersion = Objects.toString(row.get("risk_event_type_version"), "");
                if (PROJECT_AUXILIARY_OBJECTS && !riskVersion.isBlank()) {
                    String definitionId = Objects.toString(row.get("risk_event_definition_ref"), "");
                    if (definitionId.isBlank()) definitionId = "RISK_EVENT_TYPE:"
                            + Objects.toString(row.get("risk_event_type")) + ":v" + riskVersion;
                    mergeReasoningNode(session, "RiskEventTypeDefinition", definitionId,
                            "", "", "", "RISK_EVENT_TYPE_DEFINITION", "KNOWLEDGE",
                            Objects.toString(row.get("risk_event_type")), riskVersion,
                            Objects.toString(row.get("risk_event_type")) + " 风险定义",
                            "ACTIVE", "", "");
                    mergeEdge(session, graphId, definitionId, "INSTANCE_OF_RISK_EVENT_TYPE");
                }
            }

            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : alternatives) {
                String graphId = caseId + "::" + Objects.toString(row.get("explanation_id"));
                mergeReasoningNode(session, "AlternativeExplanation", graphId, caseId, bankCode,
                        workspaceId, "ALTERNATIVE_EXPLANATION", "REASONING",
                        Objects.toString(row.get("alternative_type")), "1",
                        Objects.toString(row.get("title"), Objects.toString(row.get("summary"))),
                        Objects.toString(row.get("status")),
                        Objects.toString(row.get("confidence"), ""), "");
                String riskId = Objects.toString(row.get("target_risk_event_id"), "");
                if (!riskId.isBlank()) mergeEdge(session, graphId, caseId + "::" + riskId,
                        "ALTERNATIVE_TO");
            }

            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : techniques) {
                String graphId = caseId + "::" + Objects.toString(row.get("occurrence_id"));
                String code = Objects.toString(row.get("technique_code"));
                String version = Objects.toString(row.get("technique_version"), "1.0");
                mergeReasoningNode(session, "TechniqueOccurrence", graphId, caseId, bankCode,
                        workspaceId, "TECHNIQUE_OCCURRENCE", "REASONING", code, version,
                        Objects.toString(row.get("explanation"), code),
                        Objects.toString(row.get("status")),
                        Objects.toString(row.get("mapping_confidence"), ""),
                        Objects.toString(row.get("evidence_strength"), ""));
                mergeEdge(session, caseId, graphId, "CONTAINS_TECHNIQUE_OCCURRENCE");
                String techniqueGraphId = "TECHNIQUE:" + code + ":v" + version;
                mergeReasoningNode(session, "Technique", techniqueGraphId, "", "", "",
                        "TECHNIQUE", "KNOWLEDGE", code, version,
                        Objects.toString(row.get("technique_name"), code), "ACTIVE", "", "");
                mergeEdge(session, graphId, techniqueGraphId, "INSTANCE_OF_TECHNIQUE");
            }
            List<Map<String,Object>> tacticLinks = jdbc.queryForList("""
                SELECT DISTINCT r.target_code AS technique_code,r.source_code AS tactic_code,
                       COALESCE(t.term_name,r.source_code) AS tactic_name
                FROM knowledge_asset_relation r
                LEFT JOIN ontology_term t
                  ON t.namespace='AMLTRIX_TACTIC' AND t.term_code=r.source_code
                 AND t.term_version::varchar=r.source_version
                WHERE r.relation_type='TACTIC_HAS_TECHNIQUE' AND r.status='ACTIVE'
                  AND r.target_code IN (
                    SELECT technique_code FROM technique_occurrence
                    WHERE case_id=? AND status<>'SUPERSEDED'
                  )
                """, caseId);
            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : tacticLinks) {
                String tacticCode = Objects.toString(row.get("tactic_code"));
                String tacticGraphId = "TACTIC:" + tacticCode + ":v1";
                mergeReasoningNode(session, "Tactic", tacticGraphId, "", "", "",
                        "TACTIC", "KNOWLEDGE", tacticCode, "1",
                        Objects.toString(row.get("tactic_name"), tacticCode), "ACTIVE", "", "");
                mergeEdge(session, "TECHNIQUE:" + Objects.toString(row.get("technique_code"))
                        + ":v1", tacticGraphId, "TECHNIQUE_SERVES_TACTIC");
            }
            List<Map<String,Object>> riskTechniqueLinks = jdbc.queryForList("""
                SELECT DISTINCT r.risk_event_id,t.occurrence_id
                FROM risk_event_hypothesis r
                 JOIN technique_occurrence t ON t.case_id=r.case_id
                 WHERE r.case_id=? AND r.status='ACTIVE' AND t.status<>'SUPERSEDED'
                   AND jsonb_exists(t.risk_event_refs,r.risk_event_id)
                """, caseId);
            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : riskTechniqueLinks) mergeEdge(session,
                    caseId + "::" + Objects.toString(row.get("risk_event_id")),
                    caseId + "::" + Objects.toString(row.get("occurrence_id")), "INTERPRETED_AS");

            if (PROJECT_AUXILIARY_OBJECTS) for (Map<String,Object> row : matters) {
                String graphId = caseId + "::" + Objects.toString(row.get("matter_id"));
                mergeReasoningNode(session, "Matter", graphId, caseId, bankCode, workspaceId,
                        "MATTER", "REASONING", Objects.toString(row.get("matter_type")), "1",
                        Objects.toString(row.get("summary")), Objects.toString(row.get("status")),
                        "", "");
                mergeEdge(session, caseId, graphId, "CONTAINS_MATTER");
                for (String riskId : jsonStrings(row.get("risk_event_refs"))) {
                    mergeEdge(session, graphId, caseId + "::" + riskId, "SUMMARIZES");
                }
            }

            for (Map<String,Object> row : indicators) {
                String calculationId = Objects.toString(row.get("calculation_id"));
                String graphId = caseId + "::" + calculationId;
                String code = Objects.toString(row.get("indicator_code"));
                String version = Objects.toString(row.get("indicator_version"), "1");
                mergeReasoningNode(session, "IndicatorResult", graphId, caseId, bankCode,
                        workspaceId, "INDICATOR_RESULT", "REASONING", code, version,
                        Objects.toString(row.get("indicator_name"), code),
                        Objects.toString(row.get("risk_level"), ""),
                        Objects.toString(row.get("numeric_value"), ""), "");
                mergeEdge(session, caseId, graphId, "CONTAINS_INDICATOR_RESULT");
                if (PROJECT_AUXILIARY_OBJECTS) {
                String definitionGraphId = "INDICATOR:" + code + ":v" + version;
                mergeReasoningNode(session, "IndicatorDefinition", definitionGraphId, "", "", "",
                        "INDICATOR_DEFINITION", "KNOWLEDGE", code, version,
                        Objects.toString(row.get("indicator_name"), code), "ACTIVE", "", "");
                mergeEdge(session, graphId, definitionGraphId, "INSTANCE_OF_INDICATOR");
                }
                List<String> behaviorIds = jdbc.queryForList("""
                    SELECT occurrence_id
                    FROM behavior_pattern_occurrence
                    WHERE case_id=? AND status='ACTIVE'
                      AND jsonb_exists(indicator_result_refs,?)
                    """, String.class, caseId, calculationId);
                for (String behaviorId : behaviorIds) mergeEdge(session, graphId,
                        caseId + "::" + behaviorId, "SUPPORTS_BEHAVIOR_PATTERN");
            }

            for (Map<String,Object> row : investigations) {
                String graphId = caseId + "::" + Objects.toString(row.get("hypothesis_id"));
                mergeReasoningNode(session, "InvestigationHypothesis", graphId, caseId, bankCode,
                        workspaceId, "INVESTIGATION_HYPOTHESIS", "REASONING", "INVESTIGATION", "1",
                        Objects.toString(row.get("hypothesis")), Objects.toString(row.get("status")),
                        "", "");
                mergeEdge(session, caseId, graphId, "CONTAINS_INVESTIGATION_HYPOTHESIS");
                String riskId = Objects.toString(row.get("risk_event_id"), "");
                if (!riskId.isBlank()) mergeEdge(session, caseId + "::" + riskId, graphId,
                        "RAISES_HYPOTHESIS");
            }
            List<Map<String,Object>> eventRelations = jdbc.queryForList("""
                SELECT source_event_id,relation_type,target_event_id
                FROM case_event_relation WHERE case_id=?
                ORDER BY relation_type,source_event_id,target_event_id
                """, caseId);
            for (Map<String,Object> row : eventRelations) {
                mergeEdge(session, Objects.toString(row.get("source_event_id")),
                        Objects.toString(row.get("target_event_id")),
                        Objects.toString(row.get("relation_type")));
            }
        }
        return Map.of("status", "SUCCEEDED", "behaviorPatterns", behaviors.size(),
                "riskEvents", risks.size(), "alternatives", alternatives.size(),
                "techniques", techniques.size(), "matters", matters.size(),
                "investigationHypotheses", investigations.size(),
                "semanticEvents", semanticEvents.size(), "attackPathCandidates", 0);
    }

    private void mergeReasoningNode(org.neo4j.driver.Session session, String label, String graphId,
                                    String caseId, String bankCode, String workspaceId,
                                    String nodeType, String graphPlane, String code, String version,
                                    String summary, String status, String confidence,
                                    String evidenceStrength) {
        session.run("MERGE (n:" + label + " {graphId:'" + cypherLiteral(graphId) + "'}) SET "
                + "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='" + cypherLiteral(bankCode)
                + "',n.workspaceId='" + cypherLiteral(workspaceId) + "',n.nodeType='"
                + cypherLiteral(nodeType) + "',n.graphPlane='" + cypherLiteral(graphPlane)
                + "',n.code='" + cypherLiteral(code) + "',n.version='" + cypherLiteral(version)
                + "',n.summary='" + cypherLiteral(summary) + "',n.status='" + cypherLiteral(status)
                + "',n.confidence='" + cypherLiteral(confidence) + "',n.evidenceStrength='"
                + cypherLiteral(evidenceStrength) + "'").consume();
    }

    private void mergeEdge(org.neo4j.driver.Session session, String sourceGraphId,
                           String targetGraphId, String type) {
        session.run("MATCH (a {graphId:'" + cypherLiteral(sourceGraphId)
                + "'}),(b {graphId:'" + cypherLiteral(targetGraphId)
                + "'}) MERGE (a)-[r:" + type + "]->(b)").consume();
    }

    public Map<String,Object> writeFrameworkCase(String caseId, String bankCode,
                                                  long workspaceId, JsonNode framework) {
        eventSemanticEnrichmentService.enrichCase(caseId);
        contractProjectionService.captureCase(caseId);
        ensureFrameworkSchema();
        try (var session = structuredDriver.session(SessionConfig.forDatabase("BankGraph"))) {
            // A new historical case is the overwhelmingly common path.  Probe the
            // Case primary key first so imports do not rescan the entire graph for
            // a case-scoped delete on every row.  Reruns remain idempotent.
            boolean existingCase = session.run("MATCH (n:Case {graphId:$caseId}) RETURN n LIMIT 1",
                    Map.of("caseId", caseId)).hasNext();
            if (existingCase) {
                session.run("MATCH (n) WHERE n.caseId=$caseId DETACH DELETE n",
                        Map.of("caseId", caseId)).consume();
                session.run("MATCH (n:Case {graphId:$caseId}) DETACH DELETE n",
                        Map.of("caseId", caseId)).consume();
            }

            String caseName = framework.path("basic_info").path("case_name").asText(caseId);
            String caseSummary = framework.path("basic_info").path("case_description").asText("");
            session.run("MERGE (n:Case {graphId:'" + cypherLiteral(caseId) + "'}) SET "
                    + "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='"
                    + cypherLiteral(bankCode) + "',n.workspaceId='" + workspaceId
                    + "',n.nodeType='CASE',n.name='" + cypherLiteral(caseName)
                    + "',n.summary='" + cypherLiteral(caseSummary) + "'").consume();

            Map<String,String> graphIds = new LinkedHashMap<>();
            Map<String,String> graphLabels = new LinkedHashMap<>();
            graphIds.put(caseId, caseId);
            graphLabels.put(caseId, "Case");
            int nodeCount = 1;
            nodeCount += writeFrameworkNodes(session, caseId, bankCode, workspaceId,
                    framework.path("customers"), "Customer", "CUSTOMER",
                    List.of("entity_id"), List.of("customer_name", "entity_id"), graphIds, graphLabels);
            nodeCount += writeFrameworkNodes(session, caseId, bankCode, workspaceId,
                    framework.path("accounts"), "Account", "ACCOUNT",
                    List.of("entity_id", "account_number"),
                    List.of("account_number", "holder_name", "entity_id"), graphIds, graphLabels);
            nodeCount += writeFrameworkNodes(session, caseId, bankCode, workspaceId,
                    framework.path("events"), "Event", "EVENT",
                    List.of("event_id"), List.of("event_name", "event_type", "event_id"), graphIds, graphLabels);
            nodeCount += writeFrameworkNodes(session, caseId, bankCode, workspaceId,
                    framework.path("evidences"), "Evidence", "EVIDENCE",
                    List.of("evidence_id"), List.of("evidence_type", "evidence_id"), graphIds, graphLabels);
            nodeCount += writeFrameworkNodes(session, caseId, bankCode, workspaceId,
                    framework.path("other_entities"), "OtherEntity", "OTHER_ENTITY",
                    List.of("entity_id"), List.of("entity_attr_1", "entity_id"), graphIds, graphLabels);

            int edgeCount = 0;
            int generatedRelationshipIndex = 0;
            List<Map<String,Object>> edgeRows = new ArrayList<>();
            JsonNode relationships = framework.path("relationships");
            if (relationships.isArray()) for (JsonNode relationship : relationships) {
                String sourceId = relationship.path("source_node_id").asText("");
                String targetId = relationship.path("target_node_id").asText("");
                String sourceGraphId = graphIds.get(sourceId);
                String targetGraphId = graphIds.get(targetId);
                if (sourceGraphId == null || targetGraphId == null) continue;
                String relationId = valueOr(relationship.path("relationship_id").asText(),
                        "REL-" + (++generatedRelationshipIndex));
                String relationType = relationship.path("relationship_type").asText("关联关系");
                String summary = relationship.path("relationship_description").asText("");
                addFrameworkEdgeRow(edgeRows, graphLabels, sourceGraphId, targetGraphId,
                        caseId + "::" + relationId, relationType, summary);
                edgeCount++;
            }
            for (Map.Entry<String,String> entry : graphIds.entrySet()) {
                if (entry.getKey().equals(caseId)) continue;
                addFrameworkEdgeRow(edgeRows, graphLabels, caseId, entry.getValue(),
                        caseId + "::CONTAINS::" + entry.getKey(), "案例包含", "案例框架节点");
                edgeCount++;
            }
            writeFrameworkEdges(session, edgeRows);
            return Map.of("engine", "TUGRAPH", "nodes", nodeCount, "edges", edgeCount);
        }
    }

    private void ensureFrameworkSchema() {
        if (frameworkSchemaReady.get()) return;
        synchronized (frameworkSchemaReady) {
            if (frameworkSchemaReady.get()) return;
            try (var admin = structuredDriver.session()) {
                try {
                    admin.run("CALL dbms.graph.createGraph('BankGraph','bankgraph structured AML graph',1024)").consume();
                } catch (Exception ignored) { /* graph already exists */ }
            }
            try (var session = structuredDriver.session(SessionConfig.forDatabase("BankGraph"))) {
                for (String label : List.of("Case", "Customer", "Account", "Event", "Evidence", "OtherEntity")) {
                    try {
                        session.run("CALL db.createVertexLabel('" + label
                                + "','graphId','graphId','string',false,'caseId','string',true,"
                                + "'bankCode','string',true,'workspaceId','string',true,"
                                + "'nodeType','string',true,'name','string',true,"
                                + "'summary','string',true,'eventName','string',true,"
                                + "'eventType','string',true,'eventText','string',true)").consume();
                    } catch (Exception ignored) { /* label already exists */ }
                }
                try {
                    session.run("CALL db.createEdgeLabel('RELATES_TO','[]','graphId','string',true,"
                            + "'relationType','string',true,'summary','string',true)").consume();
                } catch (Exception ignored) { /* label already exists */ }
                try {
                    // Pair-unique keeps distinct relationship ids between the
                    // same endpoints and enables TuGraph's native bulk upsert.
                    session.run("CALL db.addEdgeIndex('RELATES_TO','graphId',false,true)").consume();
                } catch (Exception ignored) { /* index already exists */ }
            }
            frameworkSchemaReady.set(true);
        }
    }

    private int writeFrameworkNodes(org.neo4j.driver.Session session, String caseId,
                                    String bankCode, long workspaceId, JsonNode nodes,
                                    String label, String nodeType, List<String> idFields,
                                    List<String> nameFields, Map<String,String> graphIds,
                                    Map<String,String> graphLabels) {
        if (!nodes.isArray()) return 0;
        List<Map<String,Object>> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            String nodeId = firstText(node, idFields);
            if (nodeId.isBlank()) continue;
            String graphId = caseId + "::" + nodeId;
            graphIds.put(nodeId, graphId);
            graphLabels.put(graphId, label);
            String name = firstText(node, nameFields);
            String summary = firstText(node, List.of(
                    "event_description", "original_data", "description", "summary"));
            String eventName = "Event".equals(label) ? node.path("event_name").asText("") : "";
            String eventType = "Event".equals(label) ? node.path("event_type").asText("") : "";
            rows.add(Map.of(
                    "graphId", graphId, "caseId", caseId, "bankCode", bankCode,
                    "workspaceId", Long.toString(workspaceId), "nodeType", nodeType,
                    "name", name, "summary", summary, "eventName", eventName,
                    "eventType", eventType, "eventText", summary));
        }
        if (!rows.isEmpty()) {
            session.run("CALL db.upsertVertex('" + label + "',$rows)",
                    Map.of("rows", rows)).consume();
        }
        return rows.size();
    }

    private void addFrameworkEdgeRow(List<Map<String,Object>> rows,
                                     Map<String,String> graphLabels,
                                     String sourceGraphId, String targetGraphId,
                                     String relationshipId, String relationType, String summary) {
        String sourceLabel = graphLabels.get(sourceGraphId);
        String targetLabel = graphLabels.get(targetGraphId);
        if (sourceLabel == null || targetLabel == null) return;
        rows.add(Map.of(
                "sourceGraphId", sourceGraphId, "targetGraphId", targetGraphId,
                "sourceLabel", sourceLabel, "targetLabel", targetLabel,
                "graphId", relationshipId, "relationType", relationType,
                "summary", summary));
    }

    private void writeFrameworkEdges(org.neo4j.driver.Session session,
                                     List<Map<String,Object>> rows) {
        Map<String,List<Map<String,Object>>> grouped = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            String key = row.get("sourceLabel") + "\u0000" + row.get("targetLabel");
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String,List<Map<String,Object>>> entry : grouped.entrySet()) {
            String[] labels = entry.getKey().split("\u0000", -1);
            List<Map<String,Object>> upsertRows = entry.getValue().stream()
                    .map(row -> Map.<String,Object>of(
                            "sourceGraphId", row.get("sourceGraphId"),
                            "targetGraphId", row.get("targetGraphId"),
                            "graphId", row.get("graphId"),
                            "relationType", row.get("relationType"),
                            "summary", row.get("summary")))
                    .toList();
            session.run("CALL db.upsertEdge('RELATES_TO',"
                            + "{type:'" + labels[0] + "',key:'sourceGraphId'},"
                            + "{type:'" + labels[1] + "',key:'targetGraphId'},$rows,'graphId')",
                    Map.of("rows", upsertRows)).consume();
        }
    }

    private void mergeFrameworkEdge(org.neo4j.driver.Session session, String sourceGraphId,
                                    String targetGraphId, String relationshipId,
                                    String relationType, String summary) {
        session.run("MATCH (a {graphId:'" + cypherLiteral(sourceGraphId)
                + "'}),(b {graphId:'" + cypherLiteral(targetGraphId)
                + "'}) MERGE (a)-[r:RELATES_TO {graphId:'" + cypherLiteral(relationshipId)
                + "'}]->(b) SET r.relationType='" + cypherLiteral(relationType)
                + "',r.summary='" + cypherLiteral(summary) + "'").consume();
    }

    private String firstText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void removeCaseSubgraph(String caseId) {
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session(SessionConfig.forDatabase("BankGraph"))) {
            // Delete every case-scoped node, including reasoning nodes added by
            // later graph-schema versions. Do not rely on a fixed label list.
            for (int attempt = 0; attempt < 10; attempt++) {
                session.run("MATCH (n) WHERE n.caseId='" + cypherLiteral(caseId)
                        + "' DETACH DELETE n").consume();
                if (!session.run("MATCH (n) WHERE n.caseId='" + cypherLiteral(caseId)
                        + "' RETURN n LIMIT 1").hasNext()) break;
            }
            // Legacy Case nodes may only carry graphId.
            session.run("MATCH (n:Case {graphId:'" + cypherLiteral(caseId)
                    + "'}) DETACH DELETE n").consume();
        }
    }

    private void createCustomerAccountOwnership(org.neo4j.driver.Session session, String caseId,
                                                  String bankCode, String accountHash, String accountId) {
        // The ingestion layer hashes account numbers before persistence.  Use that stable,
        // case-scoped identity for the customer placeholder until KYC master data is linked.
        String customerId = "CUST-" + caseId + "-" + accountHash;
        session.run("MERGE (u:Customer {graphId:'" + cypherLiteral(customerId)
                + "'}) SET u.caseId='" + cypherLiteral(caseId) + "',u.bankCode='"
                + cypherLiteral(bankCode) + "',u.nodeType='CUSTOMER'").consume();
        session.run("MATCH (u:Customer {graphId:'" + cypherLiteral(customerId)
                + "'}),(a:Account {graphId:'" + cypherLiteral(accountId)
                + "'}) MERGE (u)-[:OWNS_ACCOUNT]->(a)").consume();
    }

    public Map<String,Object> writeTextCase(String caseId, String bankCode, long workspaceId, String text) {
        eventSemanticEnrichmentService.enrichCase(caseId);
        contractProjectionService.captureCase(caseId);
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session(SessionConfig.forDatabase("BankGraph"))) {
            for (String label : List.of("Case", "Evidence", "Event")) {
                try { session.run("CALL db.createVertexLabel('" + label + "','graphId','graphId','string',false,'caseId','string',true,'bankCode','string',true,'workspaceId','string',true,'summary','string',true,'eventName','string',true,'eventType','string',true,'eventText','string',true,'nodeType','string',true)").consume(); } catch (Exception ignored) {}
            }
            for (String label : List.of("CONTAINS_EVIDENCE", "CONTAINS_EVENT")) {
                try { session.run("CALL db.createEdgeLabel('" + label + "','[]','graphId','string',true)").consume(); } catch (Exception ignored) {}
            }
            String evidence = "EVID-" + caseId;
            String event = "EVENT-" + caseId;
            String textSnippet = text.substring(0, Math.min(text.length(), 2000));
            session.run("MERGE (c:Case {graphId:'" + cypherLiteral(caseId) + "'}) SET c.caseId='" + cypherLiteral(caseId) + "',c.bankCode='" + cypherLiteral(bankCode) + "',c.workspaceId='" + workspaceId + "',c.nodeType='CASE'");
            session.run("MERGE (e:Evidence {graphId:'" + cypherLiteral(evidence) + "'}) SET e.caseId='" + cypherLiteral(caseId) + "',e.summary='" + cypherLiteral(textSnippet) + "',e.nodeType='EVIDENCE'");
            session.run("MERGE (v:Event {graphId:'" + cypherLiteral(event) + "'}) SET v.caseId='" + cypherLiteral(caseId) + "',v.eventName='WORKER_EVENT_PENDING',v.eventType='PENDING_WORKER_EXTRACTION',v.eventText='" + cypherLiteral(textSnippet) + "',v.nodeType='EVENT'");
            session.run("MATCH (c:Case {graphId:'" + cypherLiteral(caseId) + "'}),(e:Evidence {graphId:'" + cypherLiteral(evidence) + "'}) CREATE (c)-[:CONTAINS_EVIDENCE]->(e)");
            session.run("MATCH (e:Evidence {graphId:'" + cypherLiteral(evidence) + "'}),(v:Event {graphId:'" + cypherLiteral(event) + "'}) CREATE (e)-[:CONTAINS_EVENT]->(v)");
            return Map.of("engine","TUGRAPH","caseNodes",1,"evidenceNodes",1,"eventNodes",1);
        }
    }

    public Map<String,Object> writeTextCase(String caseId, String bankCode, long workspaceId, String text, JsonNode worker) {
        Map<String,Object> fallback = writeTextCase(caseId, bankCode, workspaceId, text);
        if (worker == null || !worker.has("nodes")) return fallback;
        JsonNode graph = worker.path("final").path("output");
        if (!graph.has("nodes")) graph = worker;
        Set<String> connectedNodeIds = caseConnectedNodeIds(graph);
        int caseCount=0,eventCount=0,accountCount=0,customerCount=0,evidenceCount=0,edgeCount=0;
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             var session = driver.session(SessionConfig.forDatabase("BankGraph"))) {
            session.run("MATCH (n) WHERE n.caseId='" + cypherLiteral(caseId)
                    + "' AND n.graphId<>'" + cypherLiteral(caseId)
                    + "' DETACH DELETE n").consume();
            session.run("MATCH (n {graphId:'EVID-" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
            session.run("MATCH (n {graphId:'EVENT-" + cypherLiteral(caseId) + "'}) DETACH DELETE n").consume();
            for (String label : List.of("Account", "Customer")) {
                try {
                    session.run("CALL db.createVertexLabel('" + label
                            + "','graphId','graphId','string',false,'caseId','string',true,"
                            + "'bankCode','string',true,'nodeType','string',true)").consume();
                } catch (Exception ignored) { /* label already exists */ }
            }
            JsonNode nodes = graph.path("nodes");
            Map<String,String> labels = Map.of("cases","Case","events","Event","accounts","Account","customers","Customer","evidences","Evidence");
            Map<String,String> graphIds = new HashMap<>();
            for (var entry : labels.entrySet()) {
                JsonNode list = nodes.path(entry.getKey());
                if (!list.isArray()) continue;
                for (JsonNode item : list) {
                    String uid = item.path("uid").asText("");
                    if (uid.isBlank() || !connectedNodeIds.contains(uid)) continue;
                    // Worker UIDs are deterministic for identical text. Scope
                    // physical TuGraph ids by the relational case id so reruns
                    // cannot overwrite or connect nodes owned by another case.
                    String graphId = "Case".equals(entry.getValue()) ? caseId : caseId + "::" + uid;
                    graphIds.put(uid, graphId);
                    String name = item.path("name").asText(item.path("summary").asText(uid));
                    String summary = item.path("summary").asText(item.path("event_text").asText(""));
                    String set = switch (entry.getValue()) {
                        case "Case" -> "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='" + cypherLiteral(bankCode) + "',n.nodeType='CASE'";
                        case "Evidence" -> "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='" + cypherLiteral(bankCode) + "',n.summary='" + cypherLiteral(summary) + "',n.nodeType='EVIDENCE'";
                        case "Event" -> "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='" + cypherLiteral(bankCode) + "',n.eventName='" + cypherLiteral(name) + "',n.eventText='" + cypherLiteral(summary) + "',n.nodeType='EVENT'";
                        default -> "n.caseId='" + cypherLiteral(caseId) + "',n.bankCode='" + cypherLiteral(bankCode) + "',n.nodeType='" + entry.getValue().toUpperCase() + "'";
                    };
                    session.run("MERGE (n:" + entry.getValue() + " {graphId:'" + cypherLiteral(graphId) + "'}) SET " + set).consume();
                    if ("Case".equals(entry.getValue())) caseCount++;
                    if ("Event".equals(entry.getValue())) eventCount++;
                    if ("Account".equals(entry.getValue())) accountCount++;
                    if ("Customer".equals(entry.getValue())) customerCount++;
                    if ("Evidence".equals(entry.getValue())) evidenceCount++;
                }
            }
            JsonNode edges = graph.path("edges");
            if (edges.isObject()) {
              var relations = edges.fields();
              while (relations.hasNext()) {
                var rel = relations.next();
                if (!rel.getValue().isArray()) continue;
                String type = rel.getKey().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
                try {
                    session.run("CALL db.createEdgeLabel('" + type + "', '[]')").consume();
                } catch (Exception ignored) { }
                for (JsonNode edge : rel.getValue()) {
                    String source = edge.path("source_uid").asText(edge.path("source").asText(""));
                    String target = edge.path("target_uid").asText(edge.path("target").asText(""));
                    if (source.isBlank() || target.isBlank()
                            || !connectedNodeIds.contains(source) || !connectedNodeIds.contains(target)) continue;
                    source = graphIds.getOrDefault(source, source);
                    target = graphIds.getOrDefault(target, target);
                    session.run("MATCH (a {graphId:'" + cypherLiteral(source) + "'}),(b {graphId:'" + cypherLiteral(target) + "'}) MERGE (a)-[r:" + type + "]->(b)");
                    edgeCount++;
                }
              }
            }
        }
        return Map.of("engine","TUGRAPH","caseNodes",caseCount,"eventNodes",eventCount,
                "accountNodes",accountCount,"customerNodes",customerCount,"evidenceNodes",evidenceCount,
                "edgeCount",edgeCount);
    }

    private Set<String> caseConnectedNodeIds(JsonNode graph) {
        Set<String> reachable = new HashSet<>();
        Map<String,Set<String>> adjacency = new HashMap<>();
        JsonNode cases = graph.path("nodes").path("cases");
        if (cases.isArray()) for (JsonNode item : cases) {
            String uid = item.path("uid").asText("");
            if (!uid.isBlank()) reachable.add(uid);
        }
        JsonNode edges = graph.path("edges");
        if (edges.isObject()) edges.fields().forEachRemaining(group -> {
            if (!group.getValue().isArray()) return;
            for (JsonNode edge : group.getValue()) {
                String source = edge.path("source_uid").asText(edge.path("source").asText(""));
                String target = edge.path("target_uid").asText(edge.path("target").asText(""));
                if (source.isBlank() || target.isBlank()) continue;
                adjacency.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
                adjacency.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
            }
        });
        List<String> pending = new ArrayList<>(reachable);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (reachable.add(next)) pending.add(next);
            }
        }
        return reachable;
    }

    private List<String> jsonStrings(Object value) {
        try {
            String raw = value instanceof org.postgresql.util.PGobject pg
                    ? pg.getValue() : Objects.toString(value, "[]");
            JsonNode node = new ObjectMapper().readTree(raw);
            List<String> result = new ArrayList<>();
            if (node.isArray()) node.forEach(item -> result.add(item.asText()));
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> rebuildTextCase(String caseId) {
        Map<String,Object> row = jdbc.queryForMap(
                "SELECT bank_code,workspace_id FROM cf_risk_case WHERE case_id=? AND deleted=false", caseId);
        List<String> results = jdbc.queryForList("""
                SELECT s.result_json::text
                FROM analysis_job_step s
                WHERE s.status='SUCCEEDED' AND s.result_json::text LIKE ?
                ORDER BY s.completed_at DESC NULLS LAST LIMIT 1""", String.class, "%" + caseId + "%");
        if (results.isEmpty()) throw new IllegalStateException("No persisted worker result for " + caseId);
        try {
            JsonNode root = new ObjectMapper().readTree(results.get(0));
            JsonNode worker = root.has("workerResult") ? root.get("workerResult") : root;
            Map<String,Object> facts = writeTextCase(caseId, Objects.toString(row.get("bank_code")),
                    ((Number) row.get("workspace_id")).longValue(), "", worker);
            Map<String,Object> result = new LinkedHashMap<>(facts);
            result.put("reasoning", writeReasoningGraph(caseId));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot rebuild text case graph: " + e.getMessage(), e);
        }
    }

    private String cypherLiteral(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
