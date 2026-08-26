package com.datagraph.bank.service;

import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class GraphClueAnalysisService {
    private final JdbcTemplate jdbc;
    private final Driver driver;
    private final String graphDatabase;

    public GraphClueAnalysisService(JdbcTemplate jdbc,
            @Value("${graph.tugraph.uri:bolt://localhost:7687}") String uri,
            @Value("${graph.tugraph.username:admin}") String username,
            @Value("${graph.tugraph.password:tugraph@123}") String password,
            @Value("${graph.tugraph.database:BankGraph}") String graphDatabase) {
        this.jdbc = jdbc;
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        this.graphDatabase = graphDatabase;
    }

    @PreDestroy
    public void close() { driver.close(); }

    public Map<String,Object> analyze(String bankCode, String operator) {
        return analyze(bankCode, operator, Map.of());
    }

    public Map<String,Object> analyze(String bankCode, String operator, Map<String,Object> scope) {
        String scenarioCode = Objects.toString(scope.get("scenarioCode"), "").trim();
        String startTime = Objects.toString(scope.get("startTime"), "").trim();
        String endTime = Objects.toString(scope.get("endTime"), "").trim();
        List<String> requestedCaseIds = stringList(scope.get("caseIds"));
        List<String> caseIds = resolveCaseIds(bankCode, scenarioCode, startTime, endTime, requestedCaseIds);
        if (caseIds.size() < 2) {
            throw new IllegalArgumentException("跨案直接关联分析至少需要两个案件");
        }
        String runId = "CLUE-RUN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO risk_clue_analysis_run
                  (run_id,bank_code,scenario_code,start_time,end_time,requested_case_ids,input_case_count,status,created_by)
                VALUES (?,?,NULLIF(?,''),CAST(NULLIF(?,'') AS timestamptz),CAST(NULLIF(?,'') AS timestamptz),?,?,'RUNNING',?)
                """, runId, bankCode, scenarioCode, startTime, endTime,
                requestedCaseIds.toArray(String[]::new), caseIds.size(), operator);
        Map<String,Set<String>> accountCases = new LinkedHashMap<>();
        Map<String,Set<String>> eventCases = new LinkedHashMap<>();
        int accountNodes = 0;
        int eventNodes = 0;
        try (Session session = driver.session(SessionConfig.forDatabase(graphDatabase))) {
            if (caseIds.isEmpty()) {
                jdbc.update("""
                        UPDATE risk_clue_analysis_run SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP
                        WHERE run_id=?
                        """, runId);
                return result(runId, bankCode, scenarioCode, startTime, endTime, caseIds,
                        accountNodes, eventNodes, 0, 0);
            }
            var accounts = session.run("""
                    MATCH (a:Account) WHERE a.caseId IN $caseIds
                    RETURN a.accountHash AS subject,a.caseId AS caseId LIMIT 200000
                    """, Values.parameters("caseIds", caseIds));
            while (accounts.hasNext()) {
                var row = accounts.next();
                String subject = row.get("subject").isNull() ? "" : row.get("subject").asString("");
                String caseId = row.get("caseId").isNull() ? "" : row.get("caseId").asString("");
                if (!subject.isBlank() && !caseId.isBlank()) {
                    accountCases.computeIfAbsent(subject, ignored -> new LinkedHashSet<>()).add(caseId);
                    accountNodes++;
                }
            }
            var events = session.run("""
                    MATCH (e:Event) WHERE e.caseId IN $caseIds
                    RETURN e.eventName AS eventName,e.eventType AS eventType,e.caseId AS caseId LIMIT 200000
                    """, Values.parameters("caseIds", caseIds));
            while (events.hasNext()) {
                var row = events.next();
                String name = row.get("eventName").isNull() ? "" : row.get("eventName").asString("");
                String type = row.get("eventType").isNull() ? "" : row.get("eventType").asString("");
                String caseId = row.get("caseId").isNull() ? "" : row.get("caseId").asString("");
                String motif = !type.isBlank() ? type : name;
                if (!motif.isBlank() && !caseId.isBlank()) {
                    eventCases.computeIfAbsent(motif, ignored -> new LinkedHashSet<>()).add(caseId);
                    eventNodes++;
                }
            }
            int accountClues = persistGroups(runId, bankCode, operator, "CROSS_CASE", "SHARED_ACCOUNT_V1",
                "账户跨案例复用", accountCases, 1000);
            int motifClues = persistGroups(runId, bankCode, operator, "ANOMALY_MOTIF", "REPEATED_EVENT_V1",
                "相同事件模式跨案例出现", eventCases, 1000);
            jdbc.update("""
                    UPDATE risk_clue_analysis_run SET scanned_account_nodes=?,scanned_event_nodes=?,generated_clues=?,
                      status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP WHERE run_id=?
                    """, accountNodes, eventNodes, accountClues + motifClues, runId);
            return result(runId, bankCode, scenarioCode, startTime, endTime, caseIds,
                    accountNodes, eventNodes, accountClues, motifClues);
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE risk_clue_analysis_run SET status='FAILED',error_message=?,completed_at=CURRENT_TIMESTAMP
                    WHERE run_id=?
                    """, exception.getMessage(), runId);
            throw exception;
        }
    }

    private int persistGroups(String runId, String bankCode, String operator, String clueType, String algorithm,
                              String reason, Map<String,Set<String>> groups, int limit) {
        int written = 0;
        for (Map.Entry<String,Set<String>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2 || written >= limit) continue;
            List<String> cases = new ArrayList<>(entry.getValue());
            String subjectHash = entry.getKey();
            String clueId = "CLUE-GRAPH-" + digest(clueType + ":" + subjectHash).substring(0, 24).toUpperCase();
            double confidence = Math.min(0.99, 0.55 + cases.size() * 0.08);
            String explanation = reason + "，关联" + cases.size() + "个案例；图节点标识=" + subjectHash;
            jdbc.update("""
                INSERT INTO risk_clue
                  (clue_id,bank_code,case_id,clue_type,algorithm_code,algorithm_version,source_type,analysis_run_id,
                   evidence_subgraph_ref,confidence,explanation,related_case_ids,related_subject_ids,
                   status,created_by,deleted,updated_at)
                VALUES (?,?,NULL,?,?, '1.0.0','GRAPH_ANALYSIS',?,?,?,?, ?,?, 'CANDIDATE',?,false,CURRENT_TIMESTAMP)
                ON CONFLICT (clue_id) DO UPDATE SET
                  source_type='GRAPH_ANALYSIS',analysis_run_id=EXCLUDED.analysis_run_id,
                  evidence_subgraph_ref=EXCLUDED.evidence_subgraph_ref,
                  confidence=EXCLUDED.confidence,explanation=EXCLUDED.explanation,
                  related_case_ids=EXCLUDED.related_case_ids,
                  related_subject_ids=EXCLUDED.related_subject_ids,
                  deleted=false,updated_at=CURRENT_TIMESTAMP
                """, clueId, bankCode, clueType, algorithm, runId,
                    "tugraph://" + graphDatabase + "/" + clueType + "/" + subjectHash,
                    confidence, explanation, cases.toArray(String[]::new), new String[]{subjectHash}, operator);
            written++;
        }
        return written;
    }

    private List<String> resolveCaseIds(String bankCode, String scenarioCode, String startTime,
                                        String endTime, List<String> requestedCaseIds) {
        StringBuilder sql = new StringBuilder("SELECT case_id FROM cf_risk_case WHERE deleted=false AND bank_code=?");
        List<Object> args = new ArrayList<>();
        args.add(bankCode);
        if (!scenarioCode.isBlank()) { sql.append(" AND scenario_code=?"); args.add(scenarioCode); }
        if (!startTime.isBlank()) { sql.append(" AND created_at>=CAST(? AS timestamptz)"); args.add(startTime); }
        if (!endTime.isBlank()) { sql.append(" AND created_at<=CAST(? AS timestamptz)"); args.add(endTime); }
        if (!requestedCaseIds.isEmpty()) {
            sql.append(" AND case_id IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(requestedCaseIds.size(), "?")));
            sql.append(")");
            args.addAll(requestedCaseIds);
        }
        sql.append(" ORDER BY created_at DESC LIMIT 5000");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(item -> Objects.toString(item, "").trim())
                .filter(item -> !item.isBlank()).distinct().limit(5000).toList();
    }

    private Map<String,Object> result(String runId, String bankCode, String scenarioCode,
                                      String startTime, String endTime, List<String> caseIds,
                                      int accountNodes, int eventNodes, int accountClues, int motifClues) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("engine", "TUGRAPH");
        result.put("database", graphDatabase);
        result.put("bankCode", bankCode);
        result.put("scenarioCode", scenarioCode);
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("inputCaseCount", caseIds.size());
        result.put("inputCaseIds", caseIds);
        result.put("scannedAccountNodes", accountNodes);
        result.put("scannedEventNodes", eventNodes);
        result.put("crossCaseClues", accountClues);
        result.put("motifClues", motifClues);
        result.put("generatedClues", accountClues + motifClues);
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("线索标识生成失败", exception);
        }
    }
}
