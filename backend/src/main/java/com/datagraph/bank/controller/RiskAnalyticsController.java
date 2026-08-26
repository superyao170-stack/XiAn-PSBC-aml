package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.RiskAnalyticsGateway;
import com.datagraph.bank.service.ContractProjectionService;
import com.datagraph.bank.service.Task23GraphAnalyticsService;
import com.datagraph.bank.service.Task23ScopeAnalyticsService;
import com.datagraph.bank.service.VersionedContractService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/research-analytics")
public class RiskAnalyticsController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;
    private final RiskAnalyticsGateway gateway;
    private final ContractProjectionService contractProjectionService;
    private final VersionedContractService contracts;
    private final Task23GraphAnalyticsService task23GraphAnalytics;
    private final Task23ScopeAnalyticsService task23ScopeAnalytics;

    public RiskAnalyticsController(JdbcTemplate jdbc, CurrentUser currentUser,
                                   ObjectMapper mapper, RiskAnalyticsGateway gateway,
                                   ContractProjectionService contractProjectionService,
                                   VersionedContractService contracts,
                                   Task23GraphAnalyticsService task23GraphAnalytics,
                                   Task23ScopeAnalyticsService task23ScopeAnalytics) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.gateway = gateway;
        this.contractProjectionService = contractProjectionService;
        this.contracts = contracts;
        this.task23GraphAnalytics = task23GraphAnalytics;
        this.task23ScopeAnalytics = task23ScopeAnalytics;
    }

    @GetMapping("/datasets/versions")
    public CommonResult<List<Map<String, Object>>> datasetVersions(
            @RequestParam(required = false) String bankCode,
            @RequestParam(required = false) String datasetCode,
            @RequestParam(defaultValue = "100") int limit) {
        String scoped = currentUser.scopedBankCode(bankCode);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        if (datasetCode == null || datasetCode.isBlank()) {
            return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
                SELECT dataset_version_id AS "datasetVersionId",bank_code AS "bankCode",
                       scenario_code AS "scenarioCode",dataset_code AS "datasetCode",version,
                       source_snapshot_id AS "sourceSnapshotId",schema_version AS "schemaVersion",
                       content_sha256 AS "contentSha256",record_count AS "recordCount",status,
                       created_by AS "createdBy",created_at AS "createdAt"
                FROM analytics_dataset_version
                WHERE bank_code=? ORDER BY created_at DESC LIMIT ?
                """, scoped, safeLimit)));
        }
        return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
            SELECT dataset_version_id AS "datasetVersionId",bank_code AS "bankCode",
                   scenario_code AS "scenarioCode",dataset_code AS "datasetCode",version,
                   source_snapshot_id AS "sourceSnapshotId",schema_version AS "schemaVersion",
                   content_sha256 AS "contentSha256",record_count AS "recordCount",status,
                   created_by AS "createdBy",created_at AS "createdAt"
            FROM analytics_dataset_version
            WHERE bank_code=? AND dataset_code=? ORDER BY created_at DESC LIMIT ?
            """, scoped, datasetCode, safeLimit)));
    }

    @GetMapping("/datasets/versions/{datasetVersionId}")
    public CommonResult<Map<String, Object>> datasetVersion(@PathVariable String datasetVersionId) {
        Map<String, Object> version = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT * FROM analytics_dataset_version WHERE dataset_version_id=?",
                datasetVersionId));
        currentUser.requireAccessToBank(Objects.toString(version.get("bank_code")));
        normalizeJsonFields(version, "manifest", "split_policy");
        version.put("splitCounts", jdbc.queryForList("""
                SELECT split_name AS "splitName",COUNT(*) AS count
                FROM analytics_dataset_member WHERE dataset_version_id=?
                GROUP BY split_name ORDER BY split_name
                """, datasetVersionId));
        version.put("members", normalizeJsonRows(jdbc.queryForList("""
                SELECT record_key AS "recordKey",split_name AS "splitName",
                       record_sha256 AS "recordSha256",occurred_at AS "occurredAt",label,metadata
                FROM analytics_dataset_member WHERE dataset_version_id=?
                ORDER BY split_name,record_key
                """, datasetVersionId), "metadata"));
        return CommonResult.success(version);
    }

    @PostMapping("/datasets/versions")
    public CommonResult<Map<String, Object>> createDatasetVersion(
            @RequestBody DatasetVersionRequest request) {
        if (request.datasetCode() == null || request.datasetCode().isBlank()
                || request.version() == null || request.version().isBlank()
                || request.records() == null || request.records().isEmpty())
            return CommonResult.error(400, "datasetCode, version and records are required");
        String bankCode = currentUser.scopedBankCode(request.bankCode());
        Map<String, Object> policy = request.splitPolicy() == null
                ? Map.of("train", 0.7, "validation", 0.15, "test", 0.15, "seed", "bankgraph")
                : request.splitPolicy();
        double train = ratio(policy, "train", 0.7);
        double validation = ratio(policy, "validation", 0.15);
        double test = ratio(policy, "test", 0.15);
        if (train < 0 || validation < 0 || test < 0
                || Math.abs(train + validation + test - 1.0) > 0.000001)
            return CommonResult.error(400, "splitPolicy train/validation/test must be non-negative and sum to 1");
        String seed = Objects.toString(policy.getOrDefault("seed", "bankgraph"));
        List<Map<String, Object>> records = new ArrayList<>(request.records());
        records.sort(Comparator.comparing(item -> Objects.toString(item.get("recordKey"), "")));
        if (records.stream().anyMatch(item -> Objects.toString(item.get("recordKey"), "").isBlank()))
            return CommonResult.error(400, "Every dataset record requires recordKey");
        if (records.stream().map(item -> Objects.toString(item.get("recordKey")))
                .distinct().count() != records.size())
            return CommonResult.error(400, "Dataset recordKey values must be unique");

        List<Map<String, Object>> canonicalRecords = records.stream()
                .map(TreeMap::new).map(value -> (Map<String, Object>) value).toList();
        String contentHash = sha256(json(canonicalRecords));
        String datasetVersionId = "DVER-" + UUID.randomUUID();
        String snapshotId = "ASNAP-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO analytics_dataset_snapshot
              (snapshot_id,bank_code,scenario_code,source_type,source_ref,schema_version,
               feature_definition,snapshot_sha256,record_count,metadata,created_by)
            VALUES (?,?,?,'DATASET_VERSION',?,?,?::jsonb,?,?,?::jsonb,?)
            """, snapshotId, bankCode, request.scenarioCode(), request.sourceRef(),
                request.schemaVersion(), json(request.featureDefinition()), contentHash, records.size(),
                json(Map.of("datasetCode", request.datasetCode(), "version", request.version())),
                currentUser.username());
        jdbc.update("""
            INSERT INTO analytics_dataset_version
              (dataset_version_id,bank_code,scenario_code,dataset_code,version,source_snapshot_id,
               schema_version,manifest,split_policy,content_sha256,record_count,created_by)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
            """, datasetVersionId, bankCode, request.scenarioCode(), request.datasetCode(),
                request.version(), snapshotId, request.schemaVersion(), json(Map.of(
                        "sourceRef", Objects.toString(request.sourceRef(), ""),
                        "featureDefinition", request.featureDefinition() == null
                                ? Map.of() : request.featureDefinition())),
                json(policy), contentHash, records.size(), currentUser.username());
        for (Map<String, Object> record : records) {
            String recordKey = Objects.toString(record.get("recordKey"));
            String recordHash = sha256(json(new TreeMap<>(record)));
            String split = deterministicSplit(seed, recordKey, train, validation);
            jdbc.update("""
                INSERT INTO analytics_dataset_member
                  (dataset_version_id,record_key,split_name,record_sha256,occurred_at,label,metadata)
                VALUES (?,?,?,?,?::timestamptz,?,?::jsonb)
                """, datasetVersionId, recordKey, split, recordHash,
                    record.get("occurredAt"), record.get("label"),
                    json(record.getOrDefault("metadata", Map.of())));
        }
        return datasetVersion(datasetVersionId);
    }

    @PutMapping("/datasets/versions/{datasetVersionId}/status")
    public CommonResult<Map<String, Object>> updateDatasetVersionStatus(
            @PathVariable String datasetVersionId, @RequestBody Map<String, Object> body) {
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT * FROM analytics_dataset_version WHERE dataset_version_id=?",
                datasetVersionId);
        currentUser.requireAccessToBank(Objects.toString(version.get("bank_code")));
        String status = Objects.toString(body.get("status"), "").toUpperCase();
        if (!List.of("ACTIVE", "RETIRED").contains(status))
            return CommonResult.error(400, "status must be ACTIVE or RETIRED");
        jdbc.update("UPDATE analytics_dataset_version SET status=? WHERE dataset_version_id=?",
                status, datasetVersionId);
        return datasetVersion(datasetVersionId);
    }

    @GetMapping("/runs")
    public CommonResult<List<Map<String, Object>>> runs(@RequestParam(required = false) String bankCode,
                                                        @RequestParam(required = false) String caseId,
                                                        @RequestParam(required = false) String sourceRef,
                                                        @RequestParam(defaultValue = "100") int limit) {
        String scoped = currentUser.scopedBankCode(bankCode);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String scopedSource = sourceRef == null || sourceRef.isBlank() ? caseId : sourceRef;
        if (scopedSource != null && !scopedSource.isBlank()) {
            return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
                SELECT r.run_id AS "runId",r.bank_code AS "bankCode",
                       r.scenario_code AS "scenarioCode",r.run_type AS "runType",
                       r.input_snapshot_id AS "inputSnapshotId",s.source_ref AS "sourceRef",
                       r.model_id AS "modelId",r.model_version AS "modelVersion",
                       r.algorithm_id AS "algorithmId",r.algorithm_version AS "algorithmVersion",
                       r.code_hash AS "codeHash",r.metrics,r.status,r.error_message AS "errorMessage",
                       r.started_at AS "startedAt",r.completed_at AS "completedAt"
                FROM analytics_run r
                JOIN analytics_dataset_snapshot s ON s.snapshot_id=r.input_snapshot_id
                WHERE r.bank_code=? AND s.source_ref=?
                ORDER BY r.started_at DESC LIMIT ?
                """, scoped, scopedSource, safeLimit), "metrics"));
        }
        return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
            SELECT r.run_id AS "runId",r.bank_code AS "bankCode",
                   r.scenario_code AS "scenarioCode",r.run_type AS "runType",
                   r.input_snapshot_id AS "inputSnapshotId",s.source_ref AS "sourceRef",
                   r.model_id AS "modelId",r.model_version AS "modelVersion",
                   r.algorithm_id AS "algorithmId",r.algorithm_version AS "algorithmVersion",
                   r.code_hash AS "codeHash",r.metrics,r.status,r.error_message AS "errorMessage",
                   r.started_at AS "startedAt",r.completed_at AS "completedAt"
            FROM analytics_run r
            JOIN analytics_dataset_snapshot s ON s.snapshot_id=r.input_snapshot_id
            WHERE r.bank_code=? ORDER BY r.started_at DESC LIMIT ?
            """, scoped, safeLimit), "metrics"));
    }

    @GetMapping("/cases/{caseId}/context")
    public CommonResult<Map<String, Object>> caseContext(@PathVariable String caseId) {
        currentUser.requireAccessToBank(task23GraphAnalytics.bankCode(caseId));
        Map<String, Object> context = task23GraphAnalytics.context(caseId);
        Map<String, Object> summary = new LinkedHashMap<>(context);
        summary.remove("caseGraphEnvelope");
        return CommonResult.success(summary);
    }

    @PostMapping("/cases/{caseId}/runs")
    public CommonResult<Map<String, Object>> executeCaseRun(
            @PathVariable String caseId, @RequestBody CaseRunRequest request) {
        if (request.runType() == null || request.runType().isBlank())
            return CommonResult.error(400, "runType is required");
        currentUser.requireAccessToBank(task23GraphAnalytics.bankCode(caseId));
        Map<String, Object> context = task23GraphAnalytics.context(caseId);
        String bankCode = Objects.toString(context.get("bankCode"));
        Map<String, Object> payload = task23GraphAnalytics.payload(
                caseId, request.runType(), request.parameters());
        return execute(new RunRequest(
                bankCode, Objects.toString(context.get("scenarioCode"), "UNKNOWN"),
                request.runType(), caseId, "event-matter-analytics/1.0",
                Map.of("source", "CURRENT_EVENT_AND_MATTER_GRAPH", "caseId", caseId),
                null, null, request.algorithmId(), request.algorithmVersion(),
                request.codeHash(), payload));
    }

    @PostMapping("/scopes/context")
    public CommonResult<Map<String, Object>> scopeContext(@RequestBody ScopeRequest request) {
        String bankCode = currentUser.scopedBankCode(request.bankCode());
        return CommonResult.success(task23ScopeAnalytics.publicContext(
                bankCode, request.toFilter()));
    }

    @PostMapping("/scopes/runs")
    public CommonResult<Map<String, Object>> executeScopeRun(
            @RequestBody ScopeRunRequest request) {
        if (request.runType() == null || request.runType().isBlank())
            return CommonResult.error(400, "runType is required");
        String bankCode = currentUser.scopedBankCode(request.bankCode());
        Map<String, Object> context = task23ScopeAnalytics.context(
                bankCode, request.toFilter());
        if (((Number) context.getOrDefault("caseCount", 0)).intValue() < 2) {
            return CommonResult.error(400, "关联线索分析和隐蔽风险推理至少需要两个案件");
        }
        String scopeId = Objects.toString(context.get("scopeId"));
        Map<String, Object> payload = task23ScopeAnalytics.payload(
                bankCode, request.toFilter(), request.runType(), request.parameters());
        return execute(new RunRequest(
                bankCode, Objects.toString(context.get("scenarioCode"), "MULTI_SCENARIO"),
                request.runType(), scopeId, "event-matter-analytics/1.0",
                Map.of("source", "BANK_EVENT_AND_MATTER_GRAPH", "scopeId", scopeId,
                        "caseCount", context.get("caseCount")),
                null, null, request.algorithmId(), request.algorithmVersion(),
                request.codeHash(), payload));
    }

    @GetMapping("/runs/{runId}")
    public CommonResult<Map<String, Object>> detail(@PathVariable String runId) {
        Map<String, Object> run = run(runId);
        currentUser.requireAccessToBank(Objects.toString(run.get("bank_code")));
        List<Map<String, Object>> evidence = normalizeJsonRows(jdbc.queryForList(
                "SELECT * FROM inference_evidence WHERE run_id=? ORDER BY id", runId),
                "contributions", "path_evidence");
        normalizeSqlArrayFields(evidence, "reason_codes");
        run.put("evidence", evidence);
        run.put("replays", normalizeJsonRows(jdbc.queryForList("""
                SELECT * FROM analytics_replay WHERE original_run_id=? OR replay_run_id=? ORDER BY created_at DESC
                """, runId, runId), "comparison"));
        return CommonResult.success(run);
    }

    @GetMapping("/runs/{runId}/artifacts")
    public CommonResult<List<Map<String, Object>>> artifacts(
            @PathVariable String runId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "200") int pageSize,
            @RequestParam(required = false) String artifactType) {
        Map<String, Object> run = run(runId);
        currentUser.requireAccessToBank(Objects.toString(run.get("bank_code")));
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 500);
        int offset = (safePage - 1) * safeSize;
        String typeFilter = artifactType == null || artifactType.isBlank()
                ? "" : " AND artifact_type=? ";
        List<Object> args = new ArrayList<>();
        args.add(runId);
        if (!typeFilter.isEmpty()) args.add(artifactType.trim());
        args.add(safeSize);
        args.add(offset);
        return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
            SELECT artifact_id AS "artifactId",run_id AS "runId",artifact_type AS "artifactType",
                   artifact_key AS "artifactKey",schema_version AS "schemaVersion",payload,
                   content_sha256 AS "contentSha256",review_status AS "reviewStatus",
                   reviewed_by AS "reviewedBy",reviewed_at AS "reviewedAt",created_at AS "createdAt"
            FROM analytics_artifact
            WHERE run_id=?
            """ + typeFilter + """
            ORDER BY
              COALESCE(
                CASE WHEN jsonb_typeof(payload->'score')='number'
                     THEN (payload->>'score')::numeric END,
                CASE WHEN jsonb_typeof(payload->'riskReduction')='number'
                     THEN (payload->>'riskReduction')::numeric END,
                CASE WHEN jsonb_typeof(payload->'weightedRisk')='number'
                     THEN (payload->>'weightedRisk')::numeric END,
                0) DESC,
              artifact_type,artifact_key
            LIMIT ? OFFSET ?
            """, args.toArray()), "payload"));
    }

    @PutMapping("/artifacts/{artifactId}/review")
    public CommonResult<Map<String, Object>> reviewArtifact(
            @PathVariable String artifactId, @RequestBody Map<String, Object> body) {
        Map<String, Object> artifact = jdbc.queryForMap(
                "SELECT * FROM analytics_artifact WHERE artifact_id=?", artifactId);
        currentUser.requireAccessToBank(Objects.toString(artifact.get("bank_code")));
        String status = Objects.toString(body.get("status"), "").toUpperCase();
        if (!List.of("APPROVED", "REJECTED").contains(status))
            return CommonResult.error(400, "status must be APPROVED or REJECTED");
        String artifactType = Objects.toString(artifact.get("artifact_type"));
        if (!List.of("LEARNING_CANDIDATE", "KNOWLEDGE_CANDIDATE").contains(artifactType))
            return CommonResult.error(400, "Only learning or knowledge candidates require review");
        jdbc.update("""
            UPDATE analytics_artifact SET review_status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP
            WHERE artifact_id=?
            """, status, currentUser.username(), artifactId);
        Map<String, Object> response = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT artifact_id AS "artifactId",run_id AS "runId",artifact_type AS "artifactType",
                   artifact_key AS "artifactKey",review_status AS "reviewStatus",
                   reviewed_by AS "reviewedBy",reviewed_at AS "reviewedAt"
            FROM analytics_artifact WHERE artifact_id=?
            """, artifactId));
        if ("KNOWLEDGE_CANDIDATE".equals(artifactType) && "APPROVED".equals(status)) {
            JsonNode payload = parseJson(artifact.get("payload"));
            String knowledgeCode = payload.path("knowledgeCode").asText();
            String knowledgeId = "RKE-" + UUID.randomUUID();
            String payloadJson = json(payload);
            jdbc.update("""
                INSERT INTO risk_knowledge_entry
                  (knowledge_id,bank_code,scenario_code,scope,knowledge_type,knowledge_code,
                   version,title,payload,content_sha256,source_artifact_id,status,created_by,
                   reviewed_by,reviewed_at)
                VALUES (?,?,?,'LOCAL_BANK',?,?, '1.0',?,?::jsonb,?,?,'ACTIVE',?,?,CURRENT_TIMESTAMP)
                ON CONFLICT (bank_code,knowledge_code,version) DO NOTHING
                """, knowledgeId, artifact.get("bank_code"), artifact.get("scenario_code"),
                    payload.path("knowledgeType").asText("LOCAL_RISK_PATTERN"), knowledgeCode,
                    payload.path("title").asText(knowledgeCode), payloadJson, sha256(payloadJson),
                    artifactId, currentUser.username(), currentUser.username());
            response.put("knowledgeEntry", normalizeJsonRows(jdbc.queryForList("""
                SELECT knowledge_id AS "knowledgeId",knowledge_code AS "knowledgeCode",
                       knowledge_type AS "knowledgeType",version,title,scope,status,payload,
                       source_artifact_id AS "sourceArtifactId",created_at AS "createdAt"
                FROM risk_knowledge_entry WHERE bank_code=? AND knowledge_code=? AND version='1.0'
                """, artifact.get("bank_code"), knowledgeCode), "payload").get(0));
        }
        return CommonResult.success(response);
    }

    @GetMapping("/knowledge")
    public CommonResult<List<Map<String, Object>>> knowledge(
            @RequestParam(required = false) String bankCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        String scoped = currentUser.scopedBankCode(bankCode);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        if (status == null || status.isBlank()) {
            return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
                SELECT knowledge_id AS "knowledgeId",bank_code AS "bankCode",
                       scenario_code AS "scenarioCode",scope,knowledge_type AS "knowledgeType",
                       knowledge_code AS "knowledgeCode",version,title,payload,
                       content_sha256 AS "contentSha256",source_artifact_id AS "sourceArtifactId",
                       status,created_by AS "createdBy",created_at AS "createdAt",
                       reviewed_by AS "reviewedBy",reviewed_at AS "reviewedAt"
                FROM risk_knowledge_entry WHERE bank_code=? ORDER BY created_at DESC LIMIT ?
                """, scoped, safeLimit), "payload"));
        }
        String normalized = status.toUpperCase();
        if (!List.of("ACTIVE", "RETIRED").contains(normalized))
            return CommonResult.error(400, "status must be ACTIVE or RETIRED");
        return CommonResult.success(normalizeJsonRows(jdbc.queryForList("""
            SELECT knowledge_id AS "knowledgeId",bank_code AS "bankCode",
                   scenario_code AS "scenarioCode",scope,knowledge_type AS "knowledgeType",
                   knowledge_code AS "knowledgeCode",version,title,payload,
                   content_sha256 AS "contentSha256",source_artifact_id AS "sourceArtifactId",
                   status,created_by AS "createdBy",created_at AS "createdAt",
                   reviewed_by AS "reviewedBy",reviewed_at AS "reviewedAt"
            FROM risk_knowledge_entry WHERE bank_code=? AND status=?
            ORDER BY created_at DESC LIMIT ?
            """, scoped, normalized, safeLimit), "payload"));
    }

    @GetMapping("/knowledge/{knowledgeId}")
    public CommonResult<Map<String, Object>> knowledgeDetail(@PathVariable String knowledgeId) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT knowledge_id AS "knowledgeId",bank_code AS "bankCode",
                   scenario_code AS "scenarioCode",scope,knowledge_type AS "knowledgeType",
                   knowledge_code AS "knowledgeCode",version,title,payload,
                   content_sha256 AS "contentSha256",source_artifact_id AS "sourceArtifactId",
                   status,created_by AS "createdBy",created_at AS "createdAt",
                   reviewed_by AS "reviewedBy",reviewed_at AS "reviewedAt"
            FROM risk_knowledge_entry WHERE knowledge_id=?
            """, knowledgeId));
        currentUser.requireAccessToBank(Objects.toString(result.get("bankCode")));
        normalizeJsonFields(result, "payload");
        return CommonResult.success(result);
    }

    @PutMapping("/knowledge/{knowledgeId}/status")
    public CommonResult<Map<String, Object>> updateKnowledgeStatus(
            @PathVariable String knowledgeId, @RequestBody Map<String, Object> body) {
        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT bank_code FROM risk_knowledge_entry WHERE knowledge_id=?", knowledgeId);
        currentUser.requireAccessToBank(Objects.toString(existing.get("bank_code")));
        String status = Objects.toString(body.get("status"), "").toUpperCase();
        if (!List.of("ACTIVE", "RETIRED").contains(status))
            return CommonResult.error(400, "status must be ACTIVE or RETIRED");
        jdbc.update("UPDATE risk_knowledge_entry SET status=? WHERE knowledge_id=?",
                status, knowledgeId);
        return knowledgeDetail(knowledgeId);
    }

    @PostMapping("/runs")
    public CommonResult<Map<String, Object>> execute(@RequestBody RunRequest request) {
        if (request.runType() == null || request.payload() == null)
            return CommonResult.error(400, "runType and payload are required");
        String bankCode = currentUser.scopedBankCode(request.bankCode());
        String runId = "ARUN-" + UUID.randomUUID();
        String snapshotId = "ASNAP-" + UUID.randomUUID();
        Map<String, Object> effectivePayload = normalizeTask2Events(
                request.runType(), request.payload(), bankCode, request.scenarioCode(), runId);
        effectivePayload = normalizeTask3Inputs(
                request.runType(), effectivePayload, bankCode, runId);
        String requestJson = json(effectivePayload);
        String snapshotHash = sha256(requestJson);
        long recordCount = estimateRecordCount(effectivePayload);
        jdbc.update("""
            INSERT INTO analytics_dataset_snapshot
              (snapshot_id,bank_code,scenario_code,source_type,source_ref,schema_version,
               feature_definition,snapshot_sha256,record_count,metadata,created_by)
            VALUES (?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?)
            """, snapshotId, bankCode, request.scenarioCode(), request.runType(), request.sourceRef(),
                request.schemaVersion(), json(request.featureDefinition()), snapshotHash, recordCount,
                json(Map.of("requestContentHash", snapshotHash)), currentUser.username());
        jdbc.update("""
            INSERT INTO analytics_run
              (run_id,bank_code,scenario_code,run_type,input_snapshot_id,model_id,model_version,
               algorithm_id,algorithm_version,code_hash,request_payload,status,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,'RUNNING',?)
            """, runId, bankCode, request.scenarioCode(), request.runType().toUpperCase(), snapshotId,
                request.modelId(), request.modelVersion(), request.algorithmId(), request.algorithmVersion(),
                request.codeHash(), requestJson, currentUser.username());
        try {
            JsonNode result = gateway.execute(request.runType(), mapper.valueToTree(effectivePayload));
            jdbc.update("""
                UPDATE analytics_run SET result_payload=?::jsonb,metrics=?::jsonb,status='SUCCEEDED',
                  completed_at=CURRENT_TIMESTAMP WHERE run_id=?
                """, json(result), json(result.path("metrics")), runId);
            persistEvidence(runId, request.runType().toUpperCase(), result.path("evidence"));
            persistArtifacts(runId, bankCode, request.scenarioCode(),
                    request.runType().toUpperCase(), result);
            contractProjectionService.captureInferenceRun(runId);
            return CommonResult.success(result(runId, snapshotId, result));
        } catch (RuntimeException ex) {
            jdbc.update("""
                UPDATE analytics_run SET status='FAILED',error_message=?,completed_at=CURRENT_TIMESTAMP WHERE run_id=?
                """, truncate(ex.getMessage(), 1000), runId);
            throw ex;
        }
    }

    @PostMapping("/runs/{originalRunId}/replay")
    public CommonResult<Map<String, Object>> replay(@PathVariable String originalRunId,
                                                    @RequestBody ReplayRequest request) {
        Map<String, Object> original = run(originalRunId);
        String bankCode = Objects.toString(original.get("bank_code"));
        currentUser.requireAccessToBank(bankCode);
        String originalType = Objects.toString(original.get("run_type"));
        if (!"INFERENCE".equals(originalType))
            return CommonResult.error(400, "Only INFERENCE runs can be replayed");
        JsonNode currentResult = gateway.execute("INFERENCE", mapper.valueToTree(request.payload()));
        String replayRunId = "ARUN-" + UUID.randomUUID();
        String snapshotId = "ASNAP-" + UUID.randomUUID();
        String requestJson = json(request.payload());
        String hash = sha256(requestJson);
        jdbc.update("""
            INSERT INTO analytics_dataset_snapshot
              (snapshot_id,bank_code,scenario_code,source_type,source_ref,schema_version,
               snapshot_sha256,record_count,metadata,created_by)
            VALUES (?,?,?,'REPLAY',?,?,?,0,'{}'::jsonb,?)
            """, snapshotId, bankCode, original.get("scenario_code"), originalRunId,
                request.schemaVersion(), hash, currentUser.username());
        jdbc.update("""
            INSERT INTO analytics_run
              (run_id,bank_code,scenario_code,run_type,input_snapshot_id,model_id,model_version,
               request_payload,result_payload,status,created_by,completed_at)
            VALUES (?,?,?,'INFERENCE',?,?,?,?::jsonb,?::jsonb,'SUCCEEDED',?,CURRENT_TIMESTAMP)
            """, replayRunId, bankCode, original.get("scenario_code"), snapshotId,
                request.modelId(), request.modelVersion(), requestJson, json(currentResult), currentUser.username());
        persistEvidence(replayRunId, "INFERENCE", currentResult.path("evidence"));
        contractProjectionService.captureInferenceRun(replayRunId);
        ObjectNode comparisonPayload = mapper.createObjectNode();
        comparisonPayload.set("originalResult", parseJson(original.get("result_payload")));
        comparisonPayload.set("currentResult", currentResult);
        JsonNode comparison = gateway.replay(comparisonPayload);
        String replayId = "REPLAY-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO analytics_replay(replay_id,original_run_id,replay_run_id,comparison,created_by)
            VALUES (?,?,?,?::jsonb,?)
            """, replayId, originalRunId, replayRunId, json(comparison), currentUser.username());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("replayId", replayId);
        response.put("originalRunId", originalRunId);
        response.put("replayRunId", replayRunId);
        response.put("comparison", comparison);
        return CommonResult.success(response);
    }

    private void persistEvidence(String runId, String runType, JsonNode evidence) {
        if (!evidence.isArray()) return;
        String evidenceType = switch (runType) {
            case "CASCADE_INFERENCE" -> "FUSION";
            case "INFERENCE" -> "MODEL";
            default -> "STATISTICAL";
        };
        for (JsonNode item : evidence) {
            jdbc.update("""
                INSERT INTO inference_evidence
                  (evidence_id,run_id,evidence_type,subject_id,score,decision,reason_codes,contributions,
                   path_evidence,evidence_subgraph_ref)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                """, "IEV-" + UUID.randomUUID(), runId, evidenceType,
                    item.path("subjectId").asText(null),
                    item.path("score").isNumber() ? item.path("score").decimalValue() : null,
                    item.path("decision").asText(null), toTextArray(item.path("reasonCodes")),
                    json(item.path("contributions")), json(item.path("pathEvidence")),
                    item.path("evidenceSubgraphRef").asText(null));
        }
    }

    private Map<String, Object> normalizeTask2Events(
            String runType, Map<String, Object> payload, String bankCode,
            String scenarioCode, String runId) {
        String normalizedType = runType.toUpperCase();
        if (!List.of("EVENT_CHAIN", "BEHAVIOR_MATRIX", "TEMPORAL_ANOMALY").contains(normalizedType))
            return payload;
        Object rawEvents = payload.get("events");
        if (!(rawEvents instanceof List<?> events))
            throw new IllegalArgumentException("Task2 event runs require an events array");
        List<JsonNode> canonicalEvents = new ArrayList<>();
        for (Object rawEvent : events) {
            JsonNode input = mapper.valueToTree(rawEvent);
            ObjectNode canonical;
            if ("CanonicalEvent/1.0".equals(input.path("contractVersion").asText())) {
                canonical = ((ObjectNode) input).deepCopy();
            } else {
                String eventId = input.path("eventId").asText();
                String subjectId = input.path("subjectId").asText();
                String eventType = input.path("eventType").asText();
                String occurredAt = input.path("occurredAt").asText();
                if (eventId.isBlank() || subjectId.isBlank() || eventType.isBlank() || occurredAt.isBlank())
                    throw new IllegalArgumentException(
                            "Legacy task2 events require eventId, subjectId, eventType and occurredAt");
                canonical = mapper.createObjectNode();
                canonical.put("contractVersion", "CanonicalEvent/1.0");
                canonical.put("eventId", eventId);
                canonical.put("eventKind", "ATOMIC");
                canonical.put("standardEventCode", eventType);
                canonical.putObject("scenario").put("code",
                        scenarioCode == null || scenarioCode.isBlank() ? "UNKNOWN" : scenarioCode);
                ObjectNode action = canonical.putObject("action");
                action.put("code", eventType);
                action.put("name", eventType);
                ObjectNode participant = canonical.putArray("participants").addObject();
                participant.put("entityUid", subjectId);
                participant.put("role", "SUBJECT");
                canonical.putObject("method");
                ObjectNode occurred = canonical.putObject("occurredAt");
                occurred.put("start", occurredAt);
                occurred.put("precision", "SECOND");
                canonical.putObject("location");
                canonical.putObject("result").put("status", "UNKNOWN");
                canonical.putObject("attributes");
                canonical.putArray("evidenceRefs");
                ObjectNode sourceRef = canonical.putArray("sourceRefs").addObject();
                sourceRef.put("sourceType", "LEGACY_ANALYTICS_EVENT");
                sourceRef.put("sourceRef", eventId);
                canonical.put("confidence", input.path("confidence").asDouble(1.0));
                canonical.put("normalizationVersion", "legacy-task2-adapter/1.0");
                canonical.put("dedupKey", eventId);
            }
            contracts.validate(VersionedContractService.CANONICAL_EVENT,
                    VersionedContractService.V1, canonical, bankCode,
                    "ANALYTICS_INPUT", runId).requireValid();
            canonicalEvents.add(canonical);
        }
        List<Map<String, Object>> eventContractRefs = new ArrayList<>();
        for (JsonNode event : canonicalEvents) {
            Map<String, Object> stored = contracts.saveProjected(VersionedContractService.CANONICAL_EVENT,
                    VersionedContractService.V1, bankCode, event.path("eventId").asText(),
                    event, "ANALYTICS_INPUT", runId, currentUser.username());
            eventContractRefs.add(Map.of(
                    "eventId", event.path("eventId").asText(),
                    "instanceId", stored.get("instanceId"),
                    "revision", stored.get("revision"),
                    "contentSha256", stored.get("contentSha256")));
        }
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        normalized.put("events", canonicalEvents);
        normalized.put("eventContractVersion", "CanonicalEvent/1.0");
        normalized.put("eventContractRefs", eventContractRefs);
        return normalized;
    }

    private Map<String, Object> normalizeTask3Inputs(
            String runType, Map<String, Object> payload, String bankCode, String runId) {
        String normalizedType = runType.toUpperCase();
        if (List.of("META_PATH_DETECTION", "LOCAL_HYPERGRAPH").contains(normalizedType)) {
            JsonNode envelope = mapper.valueToTree(payload.get("caseGraphEnvelope"));
            if (!envelope.isObject()
                    || !"CaseGraphEnvelope/1.0".equals(envelope.path("contractVersion").asText()))
                throw new IllegalArgumentException(
                        normalizedType + " requires caseGraphEnvelope using CaseGraphEnvelope/1.0");
            String envelopeBank = envelope.path("tenant").path("bankCode").asText();
            if (!bankCode.equals(envelopeBank))
                throw new IllegalArgumentException("caseGraphEnvelope tenant.bankCode must match run bankCode");
            contracts.validate(VersionedContractService.CASE_GRAPH,
                    VersionedContractService.V1, envelope, bankCode,
                    "ANALYTICS_INPUT", runId).requireValid();
            String businessKey = envelope.path("envelopeId").asText();
            Map<String, Object> stored = contracts.saveProjected(
                    VersionedContractService.CASE_GRAPH, VersionedContractService.V1,
                    bankCode, businessKey, envelope, "ANALYTICS_INPUT", runId,
                    currentUser.username());
            Map<String, Object> normalized = new LinkedHashMap<>(payload);
            normalized.put("caseGraphContractRef", Map.of(
                    "instanceId", stored.get("instanceId"),
                    "revision", stored.get("revision"),
                    "contentSha256", stored.get("contentSha256")));
            return normalized;
        }
        if ("CASCADE_INFERENCE".equals(normalizedType)) {
            Object rawIds = payload.get("upstreamRunIds");
            if (!(rawIds instanceof List<?> runIds) || runIds.isEmpty())
                throw new IllegalArgumentException("CASCADE_INFERENCE requires upstreamRunIds");
            List<Map<String, Object>> upstreamResults = new ArrayList<>();
            String expectedSourceRef = Objects.toString(payload.get("scopeId"), "");
            for (Object rawId : runIds) {
                String upstreamRunId = Objects.toString(rawId, "");
                Map<String, Object> upstream = run(upstreamRunId);
                if (!bankCode.equals(Objects.toString(upstream.get("bank_code"))))
                    throw new IllegalArgumentException("All cascade upstream runs must belong to the same bank");
                if (!"SUCCEEDED".equals(Objects.toString(upstream.get("status"))))
                    throw new IllegalArgumentException("Cascade upstream run must be SUCCEEDED: " + upstreamRunId);
                String upstreamType = Objects.toString(upstream.get("run_type"));
                if (!List.of("META_PATH_DETECTION", "TEMPORAL_ANOMALY", "LOCAL_HYPERGRAPH")
                        .contains(upstreamType))
                    throw new IllegalArgumentException(
                            "Unsupported cascade upstream run type: " + upstreamType);
                if (!expectedSourceRef.isBlank()) {
                    String sourceRef = jdbc.queryForObject("""
                            SELECT s.source_ref FROM analytics_run r
                            JOIN analytics_dataset_snapshot s ON s.snapshot_id=r.input_snapshot_id
                            WHERE r.run_id=?
                            """, String.class, upstreamRunId);
                    if (!expectedSourceRef.equals(sourceRef))
                        throw new IllegalArgumentException(
                                "All cascade upstream runs must belong to the same analysis scope");
                }
                upstreamResults.add(Map.of(
                        "runId", upstreamRunId,
                        "runType", upstreamType,
                        "result", upstream.get("result_payload")));
            }
            Map<String, Object> normalized = new LinkedHashMap<>(payload);
            normalized.remove("upstreamResults");
            normalized.put("upstreamResults", upstreamResults);
            return normalized;
        }
        return payload;
    }

    private void persistArtifacts(String runId, String bankCode, String scenarioCode,
                                  String runType, JsonNode result) {
        switch (runType) {
            case "EVENT_CHAIN" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "EVENT_CHAIN_TEMPLATE",
                        result.path("templates"), "templateId", false);
                persistArtifactArray(runId, bankCode, scenarioCode, "BEHAVIOR_RELATION",
                        result.path("behaviorPairs"), "relationId", false);
            }
            case "BEHAVIOR_MATRIX" -> persistArtifactArray(
                    runId, bankCode, scenarioCode, "BEHAVIOR_RELATION",
                    result.path("relations"), "relationId", false);
            case "RISK_DIFFUSION" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "RISK_PATH",
                        result.path("highRiskPaths"), null, false);
                persistArtifactArray(runId, bankCode, scenarioCode, "INTERVENTION_RESULT",
                        result.path("interventions"), "nodeId", false);
            }
            case "INCREMENTAL_LEARNING" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "TEMPLATE_UPDATE",
                        result.path("templateUpdates"), "observationId", false);
                persistArtifactArray(runId, bankCode, scenarioCode, "LEARNING_CANDIDATE",
                        result.path("candidates"), "candidateId", true);
            }
            case "META_PATH_DETECTION" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "META_PATH_DETECTION",
                        result.path("detectedPaths"), "pathId", false);
                persistArtifactArray(runId, bankCode, scenarioCode, "RISK_HUB",
                        result.path("hubScores"), "nodeId", false);
            }
            case "TEMPORAL_ANOMALY" -> persistArtifactArray(
                    runId, bankCode, scenarioCode, "TEMPORAL_ANOMALY",
                    result.path("anomalies"), "anomalyId", false);
            case "LOCAL_HYPERGRAPH" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "HYPEREDGE_RISK",
                        result.path("hyperedgeScores"), "hyperedgeId", false);
                persistArtifactArray(runId, bankCode, scenarioCode, "HYPERGRAPH_INTERVENTION",
                        result.path("interventions"), "hyperedgeId", false);
            }
            case "CASCADE_INFERENCE" -> {
                persistArtifactArray(runId, bankCode, scenarioCode, "CASCADE_DECISION",
                        result.path("decisions"), "decisionId", false);
                persistArtifactArray(runId, bankCode, scenarioCode, "KNOWLEDGE_CANDIDATE",
                        result.path("knowledgeCandidates"), "candidateId", true);
            }
            default -> { }
        }
    }

    private void persistArtifactArray(String runId, String bankCode, String scenarioCode,
                                      String artifactType, JsonNode values, String keyField,
                                      boolean requiresReview) {
        if (!values.isArray()) return;
        int index = 0;
        for (JsonNode value : values) {
            if (index >= 500) break;
            String payloadJson = json(value);
            String contentHash = sha256(payloadJson);
            String artifactKey = keyField == null ? null : value.path(keyField).asText(null);
            if (artifactKey == null || artifactKey.isBlank())
                artifactKey = artifactType + "-" + index + "-" + contentHash.substring(0, 16);
            jdbc.update("""
                INSERT INTO analytics_artifact
                  (artifact_id,run_id,bank_code,scenario_code,artifact_type,artifact_key,
                   schema_version,payload,content_sha256,review_status)
                VALUES (?,?,?,?,?,?,'1.0',?::jsonb,?,?)
                """, "AART-" + UUID.randomUUID(), runId, bankCode, scenarioCode,
                    artifactType, artifactKey, payloadJson, contentHash,
                    requiresReview ? "PENDING" : "NOT_REQUIRED");
            index++;
        }
    }

    private Map<String, Object> run(String runId) {
        Map<String, Object> result = new LinkedHashMap<>(
                jdbc.queryForMap("SELECT * FROM analytics_run WHERE run_id=?", runId));
        normalizeJsonFields(result, "request_payload", "result_payload", "metrics");
        return result;
    }

    private List<Map<String, Object>> normalizeJsonRows(List<Map<String, Object>> rows,
                                                         String... fields) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            normalizeJsonFields(copy, fields);
            normalized.add(copy);
        }
        return normalized;
    }

    private void normalizeJsonFields(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            if (row.containsKey(field) && row.get(field) != null)
                row.put(field, parseJson(row.get(field)));
        }
    }

    private void normalizeSqlArrayFields(List<Map<String, Object>> rows, String... fields) {
        for (Map<String, Object> row : rows) {
            for (String field : fields) {
                Object value = row.get(field);
                if (!(value instanceof java.sql.Array array)) continue;
                try {
                    Object raw = array.getArray();
                    row.put(field, raw instanceof Object[] values ? List.of(values) : List.of());
                } catch (java.sql.SQLException ex) {
                    throw new IllegalStateException("Stored SQL array is invalid", ex);
                }
            }
        }
    }

    private Map<String, Object> result(String runId, String snapshotId, JsonNode result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", runId);
        response.put("inputSnapshotId", snapshotId);
        response.put("status", "SUCCEEDED");
        response.put("result", result);
        return response;
    }

    private long estimateRecordCount(Map<String, Object> payload) {
        for (String key : List.of("records", "events", "subjects", "rows",
                "hyperedges", "upstreamRunIds")) {
            Object value = payload.get(key);
            if (value instanceof List<?> list) return list.size();
        }
        Object rawEnvelope = payload.get("caseGraphEnvelope");
        if (rawEnvelope instanceof Map<?, ?> envelope) {
            long count = 0;
            for (String key : List.of("entities", "events", "evidences", "relationships")) {
                Object value = envelope.get(key);
                if (value instanceof List<?> list) count += list.size();
            }
            return count;
        }
        return 0;
    }

    private String[] toTextArray(JsonNode value) {
        if (!value.isArray()) return new String[0];
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(item.asText()));
        return result.toArray(String[]::new);
    }

    private JsonNode parseJson(Object value) {
        try {
            if (value instanceof org.postgresql.util.PGobject pg) return mapper.readTree(pg.getValue());
            if (value instanceof JsonNode node) return node;
            return mapper.readTree(Objects.toString(value, "{}"));
        } catch (Exception ex) { throw new IllegalStateException("Stored analytics result is invalid", ex); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid JSON value", ex); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private double ratio(Map<String, Object> policy, String key, double fallback) {
        Object value = policy.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private String deterministicSplit(String seed, String recordKey,
                                      double trainRatio, double validationRatio) {
        long bucket = Long.parseUnsignedLong(sha256(seed + "\n" + recordKey).substring(0, 8), 16)
                % 10000;
        if (bucket < Math.round(trainRatio * 10000)) return "TRAIN";
        if (bucket < Math.round((trainRatio + validationRatio) * 10000)) return "VALIDATION";
        return "TEST";
    }

    public record RunRequest(String bankCode, String scenarioCode, String runType, String sourceRef,
                             String schemaVersion, Map<String, Object> featureDefinition,
                             String modelId, String modelVersion, String algorithmId,
                             String algorithmVersion, String codeHash, Map<String, Object> payload) {}
    public record ReplayRequest(String modelId, String modelVersion, String schemaVersion,
                                Map<String, Object> payload) {}
    public record DatasetVersionRequest(
            String bankCode, String scenarioCode, String datasetCode, String version,
            String sourceRef, String schemaVersion, Map<String, Object> featureDefinition,
            Map<String, Object> splitPolicy, List<Map<String, Object>> records) {}
    public record CaseRunRequest(String runType, Map<String, Object> parameters,
                                 String algorithmId, String algorithmVersion, String codeHash) {}
    public record ScopeRequest(String bankCode, String scenarioCode, String startTime,
                               String endTime, List<String> caseIds, Integer limit) {
        Task23ScopeAnalyticsService.ScopeFilter toFilter() {
            return new Task23ScopeAnalyticsService.ScopeFilter(
                    scenarioCode, startTime, endTime, caseIds, limit);
        }
    }
    public record ScopeRunRequest(String bankCode, String scenarioCode, String startTime,
                                  String endTime, List<String> caseIds, Integer limit,
                                  String runType, Map<String, Object> parameters,
                                  String algorithmId, String algorithmVersion, String codeHash) {
        Task23ScopeAnalyticsService.ScopeFilter toFilter() {
            return new Task23ScopeAnalyticsService.ScopeFilter(
                    scenarioCode, startTime, endTime, caseIds, limit);
        }
    }
}
