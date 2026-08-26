package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class CaseProcessingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Path amlRoot;
    private final Path fraudRoot;
    private final String amlPython;
    private final String fraudPython;
    private final long timeoutSeconds;

    public CaseProcessingService(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${worker.structured-case.root:../workers/structured-case-identification}") String amlRoot,
            @Value("${worker.anti-fraud-case.root:../workers/anti-fraud-case-identification}") String fraudRoot,
            @Value("${worker.structured-case.python:}") String amlPython,
            @Value("${worker.anti-fraud-case.python:}") String fraudPython,
            @Value("${worker.structured-case.timeout-seconds:3600}") long timeoutSeconds,
            @Value("${worker.python:python3}") String fallbackPython) {
        this.jdbc = jdbc;
        this.json = json;
        this.amlRoot = resolveRoot(amlRoot, "workers/structured-case-identification");
        this.fraudRoot = resolveRoot(fraudRoot, "workers/anti-fraud-case-identification");
        this.amlPython = WorkerPythonEnvironment.resolve(amlPython, this.amlRoot, fallbackPython);
        this.fraudPython = WorkerPythonEnvironment.resolve(fraudPython, this.fraudRoot, fallbackPython);
        this.timeoutSeconds = Math.max(60, timeoutSeconds);
    }

    private static Path resolveRoot(String configured, String projectRelative) {
        Path value = Path.of(configured);
        if (value.isAbsolute()) return value.normalize();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : List.of(cwd.resolve(value), cwd.resolve(projectRelative), cwd.resolve("../" + projectRelative))) {
            if (Files.exists(candidate)) return candidate.normalize();
        }
        return cwd.resolve(value).normalize();
    }

    public Map<String,Object> page(String stage, String recognitionMode, String scenarioCode,
                                   String caseId, String bankCode, int pageNum, int pageSize) {
        // Historical cases with a recognizable basic_info.risk_level auto-approve
        // after extraction. Missing or unknown levels go to manual approval.
        StringBuilder where = new StringBuilder(" WHERE c.deleted=false AND p.processing_stage=?");
        if (!"PENDING_APPROVAL".equals(stage)) where.append(" AND p.recognition_mode='NEW'");
        List<Object> args = new ArrayList<>();
        args.add(stage);
        if (recognitionMode != null && !recognitionMode.isBlank()) { where.append(" AND p.recognition_mode=?"); args.add(recognitionMode); }
        if (scenarioCode != null && !scenarioCode.isBlank()) { where.append(" AND p.scenario_code=?"); args.add(scenarioCode); }
        if (caseId != null && !caseId.isBlank()) { where.append(" AND (c.case_id=? OR CAST(c.id AS text)=?)"); args.add(caseId); args.add(caseId); }
        if (bankCode != null && !bankCode.isBlank()) { where.append(" AND p.bank_code=?"); args.add(bankCode); }
        int safePage = Math.max(1, pageNum), safeSize = Math.min(100, Math.max(1, pageSize));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM case_processing_pool p JOIN cf_risk_case c ON c.case_id=p.case_id" + where,
                Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args); dataArgs.add(safeSize); dataArgs.add((safePage - 1) * safeSize);
        List<Map<String,Object>> records = jdbc.queryForList("""
            SELECT c.id,c.case_id AS "caseId",c.case_name AS "caseName",c.bank_code AS "bankCode",
                   c.scenario_code AS "scenarioCode",c.case_status AS "caseStatus",c.risk_level AS "riskLevel",
                   p.recognition_mode AS "recognitionMode",p.processing_stage AS "processingStage",
                   p.recommended_risk_level AS "recommendedRiskLevel",
                   p.created_at AS "createdAt",p.updated_at AS "updatedAt"
              FROM case_processing_pool p JOIN cf_risk_case c ON c.case_id=p.case_id
            """ + where + " ORDER BY p.updated_at DESC LIMIT ? OFFSET ?", dataArgs.toArray());
        return Map.of("records", records, "total", total == null ? 0 : total,
                "pageNum", safePage, "pageSize", safeSize);
    }

    public Map<String,Object> detail(String caseId) throws Exception {
        Map<String,Object> row = jdbc.queryForMap("""
            SELECT c.id,c.case_id AS "caseId",c.case_name AS "caseName",c.bank_code AS "bankCode",
                   c.scenario_code AS "scenarioCode",c.case_status AS "caseStatus",c.risk_level AS "riskLevel",
                   p.recognition_mode AS "recognitionMode",p.processing_stage AS "processingStage",
                   p.source_payload::text AS "sourcePayload",p.suspicious_report::text AS "suspiciousReport",
                   p.framework_result::text AS "frameworkResult",p.graph_snapshot::text AS "graphSnapshot",
                   p.similarity_result::text AS "similarityResult",
                   p.recommended_risk_level AS "recommendedRiskLevel"
              FROM case_processing_pool p JOIN cf_risk_case c ON c.case_id=p.case_id
             WHERE p.case_id=? AND c.deleted=false
            """, caseId);
        Map<String,Object> result = new LinkedHashMap<>(row);
        for (String field : List.of("sourcePayload","suspiciousReport","frameworkResult","graphSnapshot","similarityResult")) {
            Object value = result.get(field);
            result.put(field, value == null ? null : json.readTree(String.valueOf(value)));
        }
        if (result.get("frameworkResult") instanceof JsonNode framework) {
            String extractedRiskLevel = riskLevelFromBasicInfo(framework);
            result.put("recommendedRiskLevel", extractedRiskLevel);
            jdbc.update("UPDATE case_processing_pool SET risk_score=NULL,recommended_risk_level=?,risk_breakdown=NULL,updated_at=CURRENT_TIMESTAMP WHERE case_id=?",
                    extractedRiskLevel, caseId);
        }
        result.put("similarityRanking", jdbc.queryForList("""
            SELECT similar_case_id AS "caseId",algorithm_rank AS "algorithmRank",final_rank AS "finalRank",
                   similarity_score AS "similarity",normalized_weight AS "weight",manually_reordered AS "manuallyReordered"
              FROM case_similarity_ranking WHERE query_case_id=? ORDER BY final_rank
            """, caseId));
        return result;
    }

    public List<Map<String,Object>> approvedSimilarityGraph(String bankCode) {
        String bankFilter = bankCode == null || bankCode.isBlank() ? "" : " AND source.bank_code=?";
        List<Object> args = bankFilter.isEmpty() ? List.of() : List.of(bankCode);
        return jdbc.queryForList("""
            SELECT ranking.query_case_id AS "sourceCaseId",ranking.similar_case_id AS "targetCaseId",
                   ranking.final_rank AS "finalRank",ranking.normalized_weight AS "weight",
                   ranking.similarity_score AS "similarity",ranking.manually_reordered AS "manuallyReordered"
              FROM case_similarity_ranking ranking
              JOIN cf_risk_case source ON source.case_id=ranking.query_case_id
              JOIN cf_risk_case target ON target.case_id=ranking.similar_case_id
             WHERE source.deleted=false AND target.deleted=false
               AND source.case_status='APPROVED' AND target.case_status='APPROVED'
            """ + bankFilter + " ORDER BY ranking.query_case_id,ranking.final_rank", args.toArray());
    }

    public Map<String,Object> process(List<String> caseIds, String action, String operator) {
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个案例");
        List<Map<String,Object>> results = new ArrayList<>();
        for (String caseId : caseIds.stream().distinct().toList()) {
            try { results.add(processOne(caseId, action, operator)); }
            catch (Exception ex) {
                // A failed attempt remains in its original business pool so the user can retry it.
                jdbc.update("UPDATE case_processing_pool SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE case_id=?",
                        truncate(ex.getMessage(), 1000), caseId);
                results.add(Map.of("caseId", caseId, "status", "FAILED", "error", truncate(ex.getMessage(), 1000)));
            }
        }
        long succeeded = results.stream().filter(item -> "SUCCEEDED".equals(item.get("status"))).count();
        return Map.of("requested", caseIds.size(), "succeeded", succeeded,
                "failed", results.size() - succeeded, "results", results);
    }

    protected Map<String,Object> processOne(String caseId, String action, String operator) throws Exception {
        Map<String,Object> pool = loadPool(caseId);
        String from = String.valueOf(pool.get("processing_stage"));
        return switch (action) {
            case "REPORT" -> runReport(pool, from, operator);
            case "FRAMEWORK" -> runFramework(pool, from, operator);
            case "SIMILARITY" -> runSimilarity(pool, from, operator);
            default -> throw new IllegalArgumentException("未知处理动作: " + action);
        };
    }

    private Map<String,Object> runReport(Map<String,Object> pool, String from, String operator) throws Exception {
        requireStage(from, "PENDING_REPORT");
        JsonNode result = invokePipeline(pool, "REPORT");
        JsonNode item = result.path("results").path(0);
        JsonNode report = item.path("suspiciousReport");
        if (!report.isObject()) throw new IllegalStateException("Worker 未返回可疑报告");
        transition(pool, from, "PENDING_EXTRACTION", "GENERATE_REPORT", operator, report);
        jdbc.update("UPDATE case_processing_pool SET suspicious_report=?::jsonb,framework_result=NULL,graph_snapshot=NULL,similarity_result=NULL,last_error=NULL WHERE case_id=?",
                json.writeValueAsString(report), pool.get("case_id"));
        return success(pool, "PENDING_EXTRACTION");
    }

    private Map<String,Object> runFramework(Map<String,Object> pool, String from, String operator) throws Exception {
        requireStage(from, "PENDING_EXTRACTION");
        JsonNode result = invokePipeline(pool, "FRAMEWORK");
        JsonNode item = result.path("results").path(0);
        JsonNode framework = item.path("extractionResult").path("data");
        JsonNode snapshot = item.path("graphSnapshot");
        if (!framework.isObject()) throw new IllegalStateException("Worker 未返回框架抽取结果");
        String caseId = String.valueOf(pool.get("case_id"));
        jdbc.update("""
            UPDATE case_processing_pool SET processing_stage='PENDING_SIMILARITY',framework_result=?::jsonb,
                   graph_snapshot=?::jsonb,similarity_result=NULL,last_error=NULL,stage_completed_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP WHERE case_id=?
            """, json.writeValueAsString(framework), json.writeValueAsString(snapshot), caseId);
        persistLibrary(pool, framework, false);
        transitionAudit(caseId, from, "PENDING_SIMILARITY", "EXTRACT_FRAMEWORK", operator, framework);
        jdbc.update("UPDATE cf_risk_case SET case_status='PENDING_SIMILARITY',updated_at=CURRENT_TIMESTAMP WHERE case_id=?", caseId);
        return success(pool, "PENDING_SIMILARITY");
    }

    private Map<String,Object> runSimilarity(Map<String,Object> pool, String from, String operator) throws Exception {
        requireStage(from, "PENDING_SIMILARITY");
        String caseId = String.valueOf(pool.get("case_id"));
        JsonNode framework = json.readTree(String.valueOf(pool.get("framework_result")));
        Files.createDirectories(amlRoot.resolve("runs"));
        Path runDir = Files.createTempDirectory(amlRoot.resolve("runs"), "similarity-stage-");
        try {
            Path query = runDir.resolve("query.json");
            Files.writeString(query, json.writeValueAsString(framework), StandardCharsets.UTF_8);
            List<String> history = historySnapshot(pool, runDir.resolve("history"));
            JsonNode similarity = invokeScript(amlRoot, amlPython, amlRoot.resolve("similarity_matching.py"),
                    Map.of("queryFile", query.toString(), "historyFiles", history, "settings", Map.of()));
            if ("FAILED".equals(similarity.path("status").asText())) throw new IllegalStateException(similarity.path("error").asText());
            ObjectNode stored = similarity.deepCopy();
            stored.set("matches", similarity.path("similarCases").deepCopy());
            String extractedRiskLevel = riskLevelFromBasicInfo(framework);
            jdbc.update("""
                UPDATE case_processing_pool SET processing_stage='PENDING_APPROVAL',similarity_result=?::jsonb,
                       risk_score=NULL,recommended_risk_level=?,risk_breakdown=NULL,last_error=NULL,
                       stage_completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE case_id=?
                """, json.writeValueAsString(stored), extractedRiskLevel, caseId);
            persistAlgorithmRanking(caseId, stored.path("matches"), operator);
            transitionAudit(caseId, from, "PENDING_APPROVAL", "MATCH_SIMILAR_CASES", operator, stored);
            jdbc.update("UPDATE cf_risk_case SET case_status='PENDING_APPROVAL',risk_level=NULL,updated_at=CURRENT_TIMESTAMP WHERE case_id=?", caseId);
            return success(pool, "PENDING_APPROVAL");
        } finally { deleteTree(runDir); }
    }

    public void updateReport(String caseId, String analysisText, String operator) throws Exception {
        if (analysisText == null || analysisText.isBlank()) throw new IllegalArgumentException("可疑报告不能为空");
        Map<String,Object> pool = loadPool(caseId);
        String from = String.valueOf(pool.get("processing_stage"));
        ObjectNode report = json.createObjectNode();
        report.put("analysisText", analysisText.trim());
        report.put("source", "USER_EDITED_ANALYSIS_TEXT");
        ObjectNode texts = json.createObjectNode(); texts.put("analysis_text", analysisText.trim());
        report.set("analysisTexts", texts);
        jdbc.update("""
            UPDATE case_processing_pool SET processing_stage='PENDING_EXTRACTION',suspicious_report=?::jsonb,
                   framework_result=NULL,graph_snapshot=NULL,similarity_result=NULL,risk_score=NULL,
                   recommended_risk_level=NULL,risk_breakdown=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP
             WHERE case_id=?
            """, json.writeValueAsString(report), caseId);
        jdbc.update("UPDATE structured_case_library SET status='INACTIVE',updated_at=CURRENT_TIMESTAMP WHERE case_id=?", caseId);
        jdbc.update("DELETE FROM case_similarity_ranking WHERE query_case_id=?", caseId);
        jdbc.update("UPDATE cf_risk_case SET case_status='PENDING_EXTRACTION',risk_score=NULL,risk_level=NULL,updated_at=CURRENT_TIMESTAMP WHERE case_id=?", caseId);
        transitionAudit(caseId, from, "PENDING_EXTRACTION", "EDIT_REPORT_AND_RESTART", operator, report);
    }

    public void approve(String caseId, String finalRiskLevel, List<String> orderedCaseIds, String operator) throws Exception {
        if (!List.of("LOW","MEDIUM","HIGH").contains(finalRiskLevel)) {
            throw new IllegalArgumentException("风险等级必须为低风险、中风险或高风险");
        }
        Map<String,Object> pool = loadPool(caseId);
        String from = String.valueOf(pool.get("processing_stage"));
        requireStage(from, "PENDING_APPROVAL");
        if (orderedCaseIds != null && !orderedCaseIds.isEmpty()) persistManualRanking(caseId, orderedCaseIds, operator);
        jdbc.update("""
            UPDATE case_processing_pool SET processing_stage='APPROVED',approved_at=CURRENT_TIMESTAMP,
                   stage_completed_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE case_id=?
            """, caseId);
        jdbc.update("""
            UPDATE cf_risk_case SET case_status='APPROVED',risk_level=?,risk_score=NULL,
                approver=?,updated_at=CURRENT_TIMESTAMP WHERE case_id=?
            """, finalRiskLevel, operator, caseId);
        jdbc.update("UPDATE structured_case_library SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE case_id=?", caseId);
        transitionAudit(caseId, from, "APPROVED", "APPROVE", operator,
                json.valueToTree(Map.of("finalRiskLevel", finalRiskLevel,
                        "orderedCaseIds", orderedCaseIds == null ? List.of() : orderedCaseIds)));
    }

    /** Default approval level comes only from final_case.basic_info.risk_level. */
    static String riskLevelFromBasicInfo(JsonNode framework) {
        if (framework == null || !framework.isObject()) return null;
        String value = framework.path("basic_info").path("risk_level").asText("").trim();
        String normalized = value.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.matches("^03(?:\\D.*)?$")
                || List.of("高", "高风险", "HIGH").contains(normalized)) return "HIGH";
        if (normalized.matches("^02(?:\\D.*)?$")
                || List.of("中", "中风险", "中等风险", "重点可疑", "MEDIUM").contains(normalized)) return "MEDIUM";
        if (normalized.matches("^01(?:\\D.*)?$")
                || List.of("低", "低风险", "一般可疑", "LOW").contains(normalized)) return "LOW";
        return null;
    }

    private JsonNode invokePipeline(Map<String,Object> pool, String targetStage) throws Exception {
        boolean fraud = "ANTI_FRAUD".equals(pool.get("scenario_code"));
        Path root = fraud ? fraudRoot : amlRoot;
        String python = fraud ? fraudPython : amlPython;
        Files.createDirectories(root.resolve("runs"));
        Path source = Files.createTempDirectory(root.resolve("runs"), "case-stage-");
        try {
            JsonNode raw = json.readTree(String.valueOf(pool.get("source_payload")));
            materializeRaw(source, raw, fraud);
            if (!"REPORT".equals(targetStage) && pool.get("suspicious_report") != null) {
                JsonNode report = json.readTree(String.valueOf(pool.get("suspicious_report")));
                JsonNode texts = report.path("analysisTexts");
                if (fraud) Files.writeString(source.resolve("text_analysis.json"),
                        json.writeValueAsString(Map.of("text", report.path("analysisText").asText())), StandardCharsets.UTF_8);
                else Files.writeString(source.resolve("analysis_texts.json"), json.writeValueAsString(texts), StandardCharsets.UTF_8);
            }
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("jobId", "STAGE-" + pool.get("case_id") + "-" + System.currentTimeMillis());
            request.put("sourcePath", source.toString()); request.put("processingMode", "SINGLE");
            request.put("recognitionMode", pool.get("recognition_mode")); request.put("targetStage", targetStage);
            request.put("historyFiles", List.of()); request.put("historySource", "POSTGRESQL");
            request.put("frameworkSettings", Map.of());
            JsonNode response = invokeScript(root, python, root.resolve("framework_extraction.py"), request);
            if ("FAILED".equals(response.path("status").asText())) throw new IllegalStateException(response.path("error").asText());
            return response;
        } finally { deleteTree(source); }
    }

    private JsonNode invokeScript(Path root, String python, Path script, Map<String,Object> request) throws Exception {
        Process process = new ProcessBuilder(python, script.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        json.writeValue(process.getOutputStream(), request); process.getOutputStream().close();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("案例阶段处理超时");
        }
        String output = outputFuture.get(10, TimeUnit.SECONDS);
        String[] lines = output.trim().split("\\R");
        String payload = lines.length == 0 ? "" : lines[lines.length - 1];
        if (payload.isBlank()) throw new IllegalStateException("Worker 未返回结果: " + truncate(output, 600));
        JsonNode result = json.readTree(payload);
        if (process.exitValue() != 0 && !"FAILED".equals(result.path("status").asText()))
            throw new IllegalStateException("Worker 执行失败: " + truncate(output, 600));
        return result;
    }

    private void materializeRaw(Path source, JsonNode raw, boolean fraud) throws Exception {
        write(source, "basic_info.json", raw.path("basic_info")); write(source, "customers.json", raw.path("customers"));
        write(source, "accounts.json", raw.path("accounts"));
        if (fraud) { write(source, "devices.json", raw.path("devices")); write(source, "event_chain.json", raw.path("event_chain")); }
        else { write(source, "other_entities.json", raw.path("other_entities")); }
    }

    private void write(Path root, String name, JsonNode value) throws Exception {
        JsonNode safe = value == null || value.isMissingNode() ? json.createArrayNode() : value;
        Files.writeString(root.resolve(name), json.writeValueAsString(safe), StandardCharsets.UTF_8);
    }

    private List<String> historySnapshot(Map<String,Object> pool, Path root) throws Exception {
        Files.createDirectories(root);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT l.case_id,l.case_document::text AS document FROM structured_case_library l
            JOIN cf_risk_case c ON c.case_id=l.case_id
            WHERE l.bank_code=? AND l.scenario_code=? AND l.status='ACTIVE' AND c.deleted=false AND c.case_status='APPROVED'
            ORDER BY l.case_id
            """, pool.get("bank_code"), pool.get("scenario_code"));
        List<String> result = new ArrayList<>(); int index = 0;
        for (Map<String,Object> row : rows) {
            Path file = root.resolve(String.format("%04d_%s.json", ++index, sha256(String.valueOf(row.get("case_id"))).substring(0,12)));
            Files.writeString(file, String.valueOf(row.get("document")), StandardCharsets.UTF_8); result.add(file.toString());
        }
        return result;
    }

    private void persistLibrary(Map<String,Object> pool, JsonNode framework, boolean active) throws Exception {
        String document = json.writeValueAsString(framework);
        jdbc.update("""
            INSERT INTO structured_case_library(case_id,bank_code,scenario_code,recognition_mode,case_document,content_sha256,status)
            VALUES (?,?,?,?,?::jsonb,?,?) ON CONFLICT (case_id) DO UPDATE SET
              case_document=EXCLUDED.case_document,content_sha256=EXCLUDED.content_sha256,status=EXCLUDED.status,
              scenario_code=EXCLUDED.scenario_code,updated_at=CURRENT_TIMESTAMP
            """, pool.get("case_id"), pool.get("bank_code"), pool.get("scenario_code"), pool.get("recognition_mode"),
                document, sha256(document), active ? "ACTIVE" : "INACTIVE");
    }

    private void persistAlgorithmRanking(String caseId, JsonNode matches, String operator) {
        jdbc.update("DELETE FROM case_similarity_ranking WHERE query_case_id=?", caseId);
        if (!matches.isArray()) return;
        int rank = 0;
        for (JsonNode match : matches) {
            String similar = match.path("caseId").asText(); if (similar.isBlank() || caseId.equals(similar)) continue;
            rank++;
            double score = match.path("similarity").asDouble(0); if (score > 1) score /= 100;
            jdbc.update("""
                INSERT INTO case_similarity_ranking(query_case_id,similar_case_id,algorithm_rank,final_rank,
                    similarity_score,normalized_weight,manually_reordered,updated_by)
                VALUES (?,?,?,?,?,?,false,?) ON CONFLICT (query_case_id,similar_case_id) DO UPDATE SET
                  algorithm_rank=EXCLUDED.algorithm_rank,final_rank=EXCLUDED.final_rank,
                  similarity_score=EXCLUDED.similarity_score,normalized_weight=EXCLUDED.normalized_weight,
                  manually_reordered=false,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """, caseId, similar, rank, rank, score, score, operator);
        }
    }

    private void persistManualRanking(String caseId, List<String> ordered, String operator) {
        List<String> distinct = ordered.stream().filter(Objects::nonNull).filter(id -> !id.equals(caseId)).distinct().toList();
        List<String> existing = jdbc.queryForList("SELECT similar_case_id FROM case_similarity_ranking WHERE query_case_id=?", String.class, caseId);
        if (distinct.size() != existing.size() || !distinct.containsAll(existing)) throw new IllegalArgumentException("人工排序必须包含全部相似案例且不能重复");
        jdbc.update("UPDATE case_similarity_ranking SET final_rank=final_rank+1000 WHERE query_case_id=?", caseId);
        double harmonic = 0;
        for (int i=0;i<distinct.size();i++) harmonic += 1.0 / (i + 1);
        for (int i=0;i<distinct.size();i++) jdbc.update("""
            UPDATE case_similarity_ranking SET final_rank=?,normalized_weight=?,manually_reordered=true,
                   updated_by=?,updated_at=CURRENT_TIMESTAMP
             WHERE query_case_id=? AND similar_case_id=?
            """, i+1, (1.0 / (i + 1)) / harmonic, operator, caseId, distinct.get(i));
    }

    private Map<String,Object> loadPool(String caseId) {
        return jdbc.queryForMap("SELECT *,source_payload::text AS source_payload,suspicious_report::text AS suspicious_report,framework_result::text AS framework_result FROM case_processing_pool WHERE case_id=?", caseId);
    }

    private void transition(Map<String,Object> pool, String from, String to, String action, String operator, JsonNode artifact) throws Exception {
        String caseId = String.valueOf(pool.get("case_id"));
        jdbc.update("UPDATE case_processing_pool SET processing_stage=?,stage_completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE case_id=?", to, caseId);
        jdbc.update("UPDATE cf_risk_case SET case_status=?,updated_at=CURRENT_TIMESTAMP WHERE case_id=?", to, caseId);
        transitionAudit(caseId, from, to, action, operator, artifact);
    }

    private void transitionAudit(String caseId, String from, String to, String action, String operator, JsonNode artifact) throws Exception {
        jdbc.update("INSERT INTO case_processing_audit(case_id,from_stage,to_stage,action,artifact_snapshot,operator_name) VALUES (?,?,?,?,?::jsonb,?)",
                caseId, from, to, action, artifact == null ? null : json.writeValueAsString(artifact), operator);
    }

    private void requireStage(String actual, String expected) {
        if (!expected.equals(actual)) throw new IllegalStateException("案例当前状态为 " + actual + "，不能执行该操作");
    }

    private Map<String,Object> success(Map<String,Object> pool, String stage) {
        return Map.of("caseId", pool.get("case_id"), "status", "SUCCEEDED", "processingStage", stage);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String truncate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0,max); }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        catch (Exception ignored) {}
    }
}
