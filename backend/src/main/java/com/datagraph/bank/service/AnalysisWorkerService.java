package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisWorkerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final TuGraphStructuredWriter tuGraphWriter;
    private final CaseMatterExplanationService matterExplanationService;
    private final EventSemanticEnrichmentService eventSemanticEnrichmentService;
    private final Path unstructuredWorkerRoot;
    private final Path structuredCaseWorkerRoot;
    private final Path antiFraudCaseWorkerRoot;
    private final String pythonCommand;
    private final String structuredCasePythonCommand;
    private final String antiFraudCasePythonCommand;
    private final long structuredCaseTimeoutSeconds;
    private boolean structuredCaseFrameworkLlmEnabled = true;
    private int structuredCaseFrameworkLlmConcurrency = 5;
    private boolean structuredCaseFrameworkCorenlpEnabled = true;
    private String structuredCaseFrameworkCorenlpUrl = "http://127.0.0.1:9002";
    private double structuredCaseFrameworkCorenlpTimeoutSeconds = 120;
    private int structuredCaseFrameworkServiceMaxAttempts = 3;
    @Value("${worker.structured-case.framework.llm-enabled:true}")
    void setStructuredCaseFrameworkLlmEnabled(boolean enabled) {
        this.structuredCaseFrameworkLlmEnabled = enabled;
    }

    @Value("${worker.structured-case.framework.llm-concurrency:5}")
    void setStructuredCaseFrameworkLlmConcurrency(int concurrency) {
        this.structuredCaseFrameworkLlmConcurrency = Math.max(1, concurrency);
    }

    @Value("${worker.structured-case.framework.corenlp-enabled:true}")
    void setStructuredCaseFrameworkCorenlpEnabled(boolean enabled) {
        this.structuredCaseFrameworkCorenlpEnabled = enabled;
    }

    @Value("${worker.structured-case.framework.corenlp-url:http://127.0.0.1:9002}")
    void setStructuredCaseFrameworkCorenlpUrl(String url) {
        this.structuredCaseFrameworkCorenlpUrl = value(url, "http://127.0.0.1:9002");
    }

    @Value("${worker.structured-case.framework.corenlp-timeout-seconds:120}")
    void setStructuredCaseFrameworkCorenlpTimeoutSeconds(double seconds) {
        this.structuredCaseFrameworkCorenlpTimeoutSeconds = Math.max(5, seconds);
    }

    @Value("${worker.structured-case.framework.service-max-attempts:3}")
    void setStructuredCaseFrameworkServiceMaxAttempts(int attempts) {
        this.structuredCaseFrameworkServiceMaxAttempts = Math.max(1, attempts);
    }

    @Autowired
    public AnalysisWorkerService(JdbcTemplate jdbc, ObjectMapper objectMapper, TaskExecutor taskExecutor,
                                 TuGraphStructuredWriter tuGraphWriter,
                                 CaseMatterExplanationService matterExplanationService,
                                 EventSemanticEnrichmentService eventSemanticEnrichmentService,
                                 @Value("${worker.unstructured.root:./worker/unstructured}") String workerRoot,
                                 @Value("${worker.structured-case.root:../workers/structured-case-identification}") String structuredCaseWorkerRoot,
                                 @Value("${worker.structured-case.python:}") String structuredCasePythonCommand,
                                 @Value("${worker.anti-fraud-case.root:../workers/anti-fraud-case-identification}") String antiFraudCaseWorkerRoot,
                                 @Value("${worker.anti-fraud-case.python:}") String antiFraudCasePythonCommand,
                                 @Value("${worker.structured-case.timeout-seconds:3600}") long structuredCaseTimeoutSeconds,
                                 @Value("${worker.python:python3}") String pythonCommand) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.tuGraphWriter = tuGraphWriter;
        this.matterExplanationService = matterExplanationService;
        this.eventSemanticEnrichmentService = eventSemanticEnrichmentService;
        this.unstructuredWorkerRoot = Path.of(workerRoot).toAbsolutePath().normalize();
        this.structuredCaseWorkerRoot = resolveStructuredCaseWorkerRoot(structuredCaseWorkerRoot);
        this.antiFraudCaseWorkerRoot = resolveWorkerRoot(
                antiFraudCaseWorkerRoot, "workers/anti-fraud-case-identification");
        this.pythonCommand = pythonCommand;
        this.structuredCasePythonCommand = resolveStructuredCasePythonCommand(
                structuredCasePythonCommand, this.structuredCaseWorkerRoot, pythonCommand);
        this.antiFraudCasePythonCommand = resolveStructuredCasePythonCommand(
                antiFraudCasePythonCommand, this.antiFraudCaseWorkerRoot, pythonCommand);
        this.structuredCaseTimeoutSeconds = Math.max(60, structuredCaseTimeoutSeconds);
    }

    AnalysisWorkerService(JdbcTemplate jdbc, ObjectMapper objectMapper, TaskExecutor taskExecutor,
                          TuGraphStructuredWriter tuGraphWriter, String workerRoot,
                          String pythonCommand) {
        this(jdbc, objectMapper, taskExecutor, tuGraphWriter, null,
                new EventSemanticEnrichmentService(jdbc, objectMapper),
                workerRoot, "../workers/structured-case-identification", "",
                "../workers/anti-fraud-case-identification", "", 3600, pythonCommand);
    }

    private static Path resolveStructuredCaseWorkerRoot(String configured) {
        return resolveWorkerRoot(configured, "workers/structured-case-identification");
    }

    private static Path resolveWorkerRoot(String configured, String projectRelative) {
        Path requested = Path.of(configured);
        if (requested.isAbsolute()) return requested.normalize();
        Path applicationRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = applicationRoot.resolve(requested).normalize();
        if (Files.exists(direct)) return direct;
        Path projectWorker = applicationRoot.resolve(projectRelative).normalize();
        if (Files.exists(projectWorker)) return projectWorker;
        return applicationRoot.resolve("../" + projectRelative).normalize();
    }

    static String resolveStructuredCasePythonCommand(
            String configured, Path workerRoot, String fallback) {
        return WorkerPythonEnvironment.resolve(configured, workerRoot, fallback);
    }

    public void executeAfterCommit(String jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { taskExecutor.execute(() -> execute(jobId)); }
            });
        } else {
            taskExecutor.execute(() -> execute(jobId));
        }
    }

    public void execute(String jobId) {
        int claimed = jdbc.update("""
                UPDATE analysis_job
                SET status='RUNNING', started_at=COALESCE(started_at, CURRENT_TIMESTAMP), current_step=NULL
                WHERE job_id=? AND status='PENDING' AND current_step='QUEUED' AND deleted=false
                """, jobId);
        if (claimed == 0) return;
        Map<String, Object> job = jdbc.queryForMap("""
                SELECT job_id, bank_code, workspace_id, batch_id, job_type, scenario_code,
                       input_params::text AS input_params
                FROM analysis_job WHERE job_id = ?
                """, jobId);
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT step_order, step_name FROM analysis_job_step WHERE job_id=? ORDER BY step_order", jobId);
        int currentOrder = 1;
        try {
            if ("UNSTRUCTURED".equals(String.valueOf(job.get("job_type")))) {
                currentOrder = steps.size();
                Map<String, Object> result = buildTextCase(job);
                markStep(jobId, currentOrder, "SUCCEEDED", 100, json(result), null);
                refreshJob(jobId);
                return;
            }
            for (Map<String, Object> step : steps) {
                currentOrder = ((Number) step.get("step_order")).intValue();
                markStep(jobId, currentOrder, "RUNNING", 25, null, null);
                Map<String, Object> result = runBusinessStage(job, currentOrder, steps.size());
                markStep(jobId, currentOrder, "SUCCEEDED", 100, json(result), null);
                refreshJob(jobId);
            }
        } catch (Exception ex) {
            String error = truncate(Objects.toString(ex.getMessage(), ex.getClass().getSimpleName()), 900);
            int marked = jdbc.update("""
                    UPDATE analysis_job_step
                    SET status='FAILED', progress=0, error_message=?,
                        completed_at=CURRENT_TIMESTAMP
                    WHERE job_id=? AND status='RUNNING'
                    """, error, jobId);
            if (marked == 0) markStep(jobId, currentOrder, "FAILED", 0, null, error);
            jdbc.update("""
                    UPDATE analysis_job SET status='FAILED', error_message=?, completed_at=CURRENT_TIMESTAMP
                    WHERE job_id=?
                    """, error, jobId);
            int failedOrder = currentOrder;
            String stepName = steps.stream()
                    .filter(step -> ((Number) step.get("step_order")).intValue() == failedOrder)
                    .map(step -> String.valueOf(step.get("step_name"))).findFirst().orElse(null);
            try {
                jdbc.update("""
                    INSERT INTO upload_job_failure_attempt
                        (job_id,failed_step_order,failed_step_name,error_message,failure_details)
                    VALUES (?,?,?,?,?::jsonb)
                    """, jobId, failedOrder, stepName, error, json(Map.of(
                        "exceptionType", ex.getClass().getSimpleName(),
                        "message", error,
                        "stepOrder", failedOrder)));
            } catch (Exception auditError) {
                // The job failure remains authoritative even if auxiliary audit persistence fails.
            }
        }
    }

    private Map<String, Object> runBusinessStage(Map<String, Object> job, int order, int totalSteps) throws Exception {
        if (order < totalSteps) {
            return Map.of("stage", order, "validated", true);
        }
        String type = String.valueOf(job.get("job_type"));
        return switch (type) {
            case "IDENTIFICATION", "PATTERN" -> throw new IllegalArgumentException(
                    "旧交易识别/聚类流程已退役");
            case "STRUCTURED" -> {
                if (!isStructuredCasePipeline(job)) {
                    throw new IllegalArgumentException(
                            "结构化案例识别工作流无效");
                }
                yield runXiAnStructuredPipeline(job);
            }
            case "UNSTRUCTURED" -> buildTextCase(job);
            default -> throw new IllegalArgumentException("Unsupported analysis job type: " + type);
        };
    }

    private boolean isStructuredCasePipeline(Map<String, Object> job) {
        try {
            JsonNode input = objectMapper.readTree(value(job.get("input_params"), "{}"));
            return List.of("XI_AN_CASE_PIPELINE", "ANTI_FRAUD_CASE_PIPELINE")
                    .contains(input.path("workflow").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runXiAnStructuredPipeline(Map<String, Object> job) throws Exception {
        JsonNode input = objectMapper.readTree(value(job.get("input_params"), "{}"));
        boolean antiFraud = "ANTI_FRAUD_CASE_PIPELINE".equals(input.path("workflow").asText());
        Path caseWorkerRoot = antiFraud ? antiFraudCaseWorkerRoot : structuredCaseWorkerRoot;
        String token = input.path("uploadToken").asText("").trim();
        String mode = input.path("processingMode").asText("SINGLE").trim().toUpperCase();
        String recognitionMode = input.path("recognitionMode").asText("").trim().toUpperCase();
        if (!token.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalStateException("结构化案例上传凭据无效");
        }
        if (!List.of("SINGLE", "BATCH").contains(mode)) {
            throw new IllegalStateException("处理方式必须是单案例或批处理");
        }
        if (!List.of("NEW", "HISTORICAL").contains(recognitionMode)) {
            throw new IllegalStateException("识别类型必须是新增案例识别或历史案例识别");
        }
        Path uploadRoot = caseWorkerRoot.resolve("uploads").normalize();
        Path sourceDirectory = uploadRoot.resolve(token).normalize();
        if (!sourceDirectory.startsWith(uploadRoot) || !Files.isDirectory(sourceDirectory)) {
            throw new IllegalStateException("结构化案例上传目录不存在或已失效");
        }
        Path source;
        if ("SINGLE".equals(mode)) {
            source = sourceDirectory;
            List<String> required = antiFraud
                    ? List.of("basic_info.json", "customers.json", "accounts.json", "devices.json")
                    : List.of("basic_info.json", "customers.json");
            if (required.stream().anyMatch(name -> !Files.isRegularFile(source.resolve(name)))) {
                throw new IllegalStateException("单案例处理缺少必填 JSON 文件");
            }
            if ("HISTORICAL".equals(recognitionMode)) {
                String reportFile = antiFraud ? "text_analysis.json" : "analysis_texts.json";
                if (!Files.isRegularFile(source.resolve(reportFile))) {
                    throw new IllegalStateException("历史案例缺少已有分析文本文件 " + reportFile);
                }
            }
        } else {
            List<Path> candidates;
            try (var files = Files.list(sourceDirectory)) {
                candidates = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().matches(".*\\.(csv|xlsx)$"))
                        .toList();
            }
            if (candidates.size() != 1) {
                throw new IllegalStateException("批处理上传目录必须且只能包含一个 CSV/XLSX 文件");
            }
            source = candidates.get(0);
        }
        String newInputFile = antiFraud ? "event_chain.json" : null;
        if ("SINGLE".equals(mode) && antiFraud && "NEW".equals(recognitionMode)
                && !Files.isRegularFile(sourceDirectory.resolve(newInputFile))) {
            throw new IllegalStateException("新增反欺诈案例缺少 event_chain.json");
        }
        int caseCount = Math.max(1, input.path("caseCount").asInt(1));
        long budgetSeconds = Math.min(3500L, Math.max(540L, caseCount * 180L));
        Map<String, Object> workerRequest = new java.util.LinkedHashMap<>();
        workerRequest.put("jobId", String.valueOf(job.get("job_id")));
        workerRequest.put("sourcePath", source.toString());
        workerRequest.put("processingMode", mode);
        workerRequest.put("recognitionMode", recognitionMode);
        workerRequest.put("targetStage", "HISTORICAL".equals(recognitionMode) ? "FRAMEWORK" : "UPLOAD");
        workerRequest.put("pipelineBudgetSeconds", budgetSeconds);
        // Historical ingestion stops at FRAMEWORK. Similarity is a later,
        // explicit stage, so copying the entire PostgreSQL history corpus for
        // every five-case import batch is both unused and quadratic in size.
        List<String> historyFiles = "HISTORICAL".equals(recognitionMode)
                ? List.of()
                : materializePostgresHistorySnapshot(job, caseWorkerRoot);
        workerRequest.put("historyFiles", historyFiles);
        workerRequest.put("historySource", "POSTGRESQL");
        workerRequest.put("streamResults", true);
        workerRequest.put("frameworkSettings", structuredCaseFrameworkSettings());
        JsonNode response = invokeStructuredCaseWorker(workerRequest, job,
                caseWorkerRoot, antiFraud ? antiFraudCasePythonCommand : structuredCasePythonCommand);
        if ("FAILED".equals(response.path("status").asText())) {
            throw new IllegalStateException("结构化案例识别执行失败："
                    + truncate(response.path("error").asText("未知错误"), 900));
        }
        int persistedCount = 0;
        for (JsonNode result : response.path("results")) {
            persistedCount++;
            if (result.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("persisted", true);
                JsonNode completedFramework = result.path("extractionResult").path("data");
                if (!completedFramework.isObject()) completedFramework = result.path("frameworkExtraction");
                boolean historicalAutoApproved = "HISTORICAL".equals(recognitionMode)
                        && CaseProcessingService.riskLevelFromBasicInfo(completedFramework) != null;
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("workflowStatus",
                        historicalAutoApproved ? "APPROVED"
                                : "HISTORICAL".equals(recognitionMode) ? "PENDING_APPROVAL" : "PENDING_REPORT");
            }
        }
        if (response.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("persistedCaseCount", persistedCount);
        }
        return objectMapper.convertValue(response, Map.class);
    }

    Map<String, Object> structuredCaseFrameworkSettings() {
        return Map.of(
                "llmEnabled", structuredCaseFrameworkLlmEnabled,
                "llmConcurrency", structuredCaseFrameworkLlmConcurrency,
                "corenlpEnabled", structuredCaseFrameworkCorenlpEnabled,
                "corenlpUrl", structuredCaseFrameworkCorenlpUrl,
                "corenlpTimeoutSeconds", structuredCaseFrameworkCorenlpTimeoutSeconds,
                "serviceMaxAttempts", structuredCaseFrameworkServiceMaxAttempts);
    }

    private List<String> materializePostgresHistorySnapshot(
            Map<String, Object> job, Path caseWorkerRoot) throws Exception {
        String bank = String.valueOf(job.get("bank_code"));
        String scenario = value(job.get("scenario_code"), "AML");
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.case_id,l.case_document::text AS case_document
             FROM structured_case_library l
              JOIN cf_risk_case c ON c.case_id=l.case_id
             WHERE l.bank_code=? AND l.scenario_code=? AND l.status='ACTIVE'
               AND c.deleted=false AND c.case_status='APPROVED'
             ORDER BY l.case_id
            """, bank, scenario);
        Path snapshotRoot = caseWorkerRoot.resolve("runs")
                .resolve(String.valueOf(job.get("job_id")))
                .resolve("pg-history-" + UUID.randomUUID()).normalize();
        if (!snapshotRoot.startsWith(caseWorkerRoot.resolve("runs").normalize())) {
            throw new IllegalStateException("PostgreSQL历史案例快照路径非法");
        }
        Files.createDirectories(snapshotRoot);
        List<String> files = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            String caseId = String.valueOf(row.get("case_id"));
            String document = String.valueOf(row.get("case_document"));
            JsonNode parsed = objectMapper.readTree(document);
            if (!parsed.isObject() || parsed.path("basic_info").path("case_id").asText("").isBlank()) {
                throw new IllegalStateException("PostgreSQL历史案例文档无效: " + caseId);
            }
            Path target = snapshotRoot.resolve(String.format("%04d_%s.json", ++index,
                    sha256(caseId).substring(0, 16))).normalize();
            Files.writeString(target, objectMapper.writeValueAsString(parsed), StandardCharsets.UTF_8);
            files.add(target.toString());
        }
        return files;
    }

    public Map<String, Object> validateStructuredCaseBatch(Path source, String recognitionMode) throws Exception {
        JsonNode response = invokeStructuredCaseWorker(Map.of(
                "sourcePath", source.toAbsolutePath().normalize().toString(),
                "processingMode", "BATCH",
                "recognitionMode", recognitionMode,
                "pipelineBudgetSeconds", 60,
                "validateOnly", true), null, structuredCaseWorkerRoot, structuredCasePythonCommand);
        if ("FAILED".equals(response.path("status").asText())) {
            throw new IllegalArgumentException(response.path("error").asText("批处理文件校验失败"));
        }
        return objectMapper.convertValue(response, Map.class);
    }

    public Map<String, Object> validateAntiFraudCaseBatch(Path source, String recognitionMode) throws Exception {
        JsonNode response = invokeStructuredCaseWorker(Map.of(
                "sourcePath", source.toAbsolutePath().normalize().toString(),
                "processingMode", "BATCH",
                "recognitionMode", recognitionMode,
                "pipelineBudgetSeconds", 60,
                "validateOnly", true), null, antiFraudCaseWorkerRoot, antiFraudCasePythonCommand);
        if ("FAILED".equals(response.path("status").asText())) {
            throw new IllegalArgumentException(response.path("error").asText("反欺诈批处理文件校验失败"));
        }
        return objectMapper.convertValue(response, Map.class);
    }

    private JsonNode invokeStructuredCaseWorker(Map<String, Object> request, Map<String, Object> job,
                                                Path caseWorkerRoot, String casePythonCommand) throws Exception {
        Path entrypoint = caseWorkerRoot.resolve("framework_extraction.py").normalize();
        if (!entrypoint.startsWith(caseWorkerRoot) || !Files.isRegularFile(entrypoint)) {
            throw new IllegalStateException("结构化案例识别 Worker 未部署: " + entrypoint);
        }
        Process process = new ProcessBuilder(casePythonCommand, entrypoint.toString())
                .directory(caseWorkerRoot.toFile()).start();
        List<String> persistenceErrors = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> {
            String finalResponse = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode message = objectMapper.readTree(line);
                    if ("CASE_COMPLETED".equals(message.path("type").asText()) && job != null) {
                        try {
                            persistXiAnStructuredCase(job, message.path("case"));
                            int total = Math.max(1, message.path("total").asInt(1));
                            int completed = Math.min(total, message.path("completed").asInt(0));
                            int progress = Math.max(25, Math.min(99, completed * 100 / total));
                            jdbc.update("UPDATE analysis_job_step SET progress=? WHERE job_id=? AND status='RUNNING'",
                                    progress, job.get("job_id"));
                            jdbc.update("UPDATE analysis_job SET progress=GREATEST(progress,?) WHERE job_id=?",
                                    progress, job.get("job_id"));
                        } catch (Exception ex) {
                            persistenceErrors.add(truncate(ex.getMessage(), 500));
                        }
                    } else {
                        finalResponse = line;
                    }
                }
                return finalResponse;
            } catch (Exception ex) {
                throw new IllegalStateException("读取结构化Worker响应失败", ex);
            }
        });
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception ex) { return ""; }
        });
        objectMapper.writeValue(process.getOutputStream(), request);
        process.getOutputStream().close();
        if (!process.waitFor(structuredCaseTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("结构化案例识别执行超时（" + structuredCaseTimeoutSeconds + "秒）");
        }
        String responseText = stdout.get(10, TimeUnit.SECONDS).trim();
        String errorText = stderr.get(10, TimeUnit.SECONDS).trim();
        if (!persistenceErrors.isEmpty()) {
            throw new IllegalStateException("案例分析完成但入库失败：" + persistenceErrors.get(0));
        }
        if (responseText.isBlank()) throw new IllegalStateException("结构化案例Worker未返回结果"
                + (errorText.isBlank() ? "" : "：" + truncate(errorText, 900)));
        JsonNode response = objectMapper.readTree(responseText);
        if (process.exitValue() != 0 && !"FAILED".equals(response.path("status").asText())) {
            throw new IllegalStateException("结构化案例识别执行失败：" + truncate(errorText, 900));
        }
        return response;
    }

    boolean persistXiAnStructuredCase(Map<String, Object> job, JsonNode result) throws Exception {
        JsonNode framework = result.path("extractionResult").path("data");
        if (!framework.isObject()) framework = result.path("frameworkExtraction");
        JsonNode rawRecord = result.path("rawRecord");
        if (!framework.isObject() && rawRecord.isObject()) {
            return persistUploadedStructuredCase(job, result, rawRecord);
        }
        JsonNode basic = framework.path("basic_info");
        String recognitionMode = value(result.path("recognitionMode").asText(), "HISTORICAL")
                .trim().toUpperCase();
        boolean historical = "HISTORICAL".equals(recognitionMode);
        String caseId = truncate(value(result.path("caseId").asText(), basic.path("case_id").asText()), 64);
        if (caseId == null || caseId.isBlank()) throw new IllegalStateException("结构化案例结果缺少 caseId");
        String bank = String.valueOf(job.get("bank_code"));
        List<String> existingBanks = jdbc.queryForList(
                "SELECT bank_code FROM cf_risk_case WHERE case_id=?", String.class, caseId);
        if (!existingBanks.isEmpty() && !bank.equals(existingBanks.get(0))) {
            throw new IllegalStateException("案例 " + caseId + " 已属于其他银行，不能覆盖入库");
        }
        String caseName = truncate(normalizeStructuredCaseName(value(result.path("caseName").asText(),
                basic.path("case_name").asText(caseId))), 240);
        // case_description is base-case metadata. The suspicious report has its
        // own analysis_texts field and must never be used as a description fallback.
        String description = truncate(basic.path("case_description").asText(), 100_000);
        String riskLevel = CaseProcessingService.riskLevelFromBasicInfo(framework);
        boolean historicalAutoApproved = historical && riskLevel != null;
        String caseStatus = historicalAutoApproved ? "APPROVED" : "PENDING_APPROVAL";
        String automaticApprover = historicalAutoApproved ? "analysis-worker" : null;
        BigDecimal riskScore = null;
        JsonNode snapshot = result.path("graphSnapshot");
        String snapshotHash = sha256(objectMapper.writeValueAsString(snapshot));
        String snapshotId = "GS-XI-" + sha256(caseId + "\u0000" + snapshotHash)
                .substring(0, 40).toUpperCase();
        String reportedAt = normalizeReportedAt(basic.path("report_date").asText());
        jdbc.update("""
            INSERT INTO cf_risk_case
            (case_id,case_version,case_name,source_case_no,description,bank_code,workspace_id,
             scenario_code,case_source,case_type,case_status,risk_score,risk_level,subject_count,
             transaction_count,graph_snapshot_id,graph_snapshot_sha256,owner_analyst,
             approver,
             struct_decision,decision_conflict,deleted,business_domain,business_case_type,
             trigger_point,reported_at,business_case_status,business_risk_level,suspected_crime_type,
             suspicious_transaction_feature_code,disposal_measure)
            VALUES (?,1,?,?,?,?,?,?,'STRUCTURED_CASE','STRUCTURED_CASE',?,?,?,?,?,?,?,
                    'analysis-worker',?,'SUSPECTED',false,false,?,?,?,NULLIF(?,'')::timestamptz,?,?,?,?,?)
            ON CONFLICT (case_id) DO UPDATE SET
              case_name=EXCLUDED.case_name,
              source_case_no=EXCLUDED.source_case_no,
              description=EXCLUDED.description,
              bank_code=EXCLUDED.bank_code,
              workspace_id=EXCLUDED.workspace_id,
              scenario_code=EXCLUDED.scenario_code,
              risk_score=EXCLUDED.risk_score,
              case_status=EXCLUDED.case_status,
              risk_level=EXCLUDED.risk_level,
              approver=EXCLUDED.approver,
              subject_count=EXCLUDED.subject_count,
              transaction_count=EXCLUDED.transaction_count,
              graph_snapshot_id=EXCLUDED.graph_snapshot_id,
              graph_snapshot_sha256=EXCLUDED.graph_snapshot_sha256,
              business_domain=EXCLUDED.business_domain,
              business_case_type=EXCLUDED.business_case_type,
              trigger_point=EXCLUDED.trigger_point,
              reported_at=EXCLUDED.reported_at,
              business_risk_level=EXCLUDED.business_risk_level,
              suspected_crime_type=EXCLUDED.suspected_crime_type,
              suspicious_transaction_feature_code=EXCLUDED.suspicious_transaction_feature_code,
              disposal_measure=EXCLUDED.disposal_measure,
              updated_at=CURRENT_TIMESTAMP,
              deleted=false
            """, caseId, caseName, caseId, description, bank, job.get("workspace_id"),
                value(job.get("scenario_code"), "AML"), caseStatus, riskScore, riskLevel,
                framework.path("customers").size(),
                basic.path("structured_transaction_summary").path("tx_cnt_30d")
                        .asInt(framework.path("events").size()),
                snapshotId, snapshotHash, automaticApprover,
                normalizeStructuredBusinessDomain(basic.path("business_domain").asText(),
                        value(job.get("scenario_code"), "AML")),
                truncate(basic.path("case_type").asText(), 64),
                truncate(basic.path("case_trigger").asText(), 255), reportedAt,
                truncate(basic.path("case_status").asText(), 64),
                truncate(basic.path("risk_level").asText(), 64),
                truncate(basic.path("suspected_crime_type").asText(), 255),
                truncate(jsonScalarOrArray(basic.path("suspicious_transaction_codes")), 4000),
                truncate(basic.path("disposition_measures").asText(), 4000));
        jdbc.update("""
            INSERT INTO graph_snapshot
            (snapshot_id,bank_code,case_id,engine_type,snapshot_sha256,node_count,edge_count,
             snapshot_size,metadata,status,completed_at)
            VALUES (?,?,?,'WORKER_JSON',?,?,?,?,?::jsonb,'COMPLETED',CURRENT_TIMESTAMP)
            ON CONFLICT (snapshot_id) DO NOTHING
            """, snapshotId, bank, caseId, snapshotHash,
                snapshot.path("nodeCount").asInt(), snapshot.path("edgeCount").asInt(),
                objectMapper.writeValueAsBytes(snapshot).length,
                objectMapper.writeValueAsString(snapshot));
        // A repeated recognition run is a new authoritative extraction of the
        // same case. Refresh its framework events even when the case row already
        // exists; the old INSERT-only behavior left local-fallback events visible
        // forever after a later successful LLM run.
        persistXiAnEvents(caseId, bank, framework.path("events"), riskScore, riskLevel);
        // analysis_texts is the suspicious report for both recognition modes:
        // HISTORICAL reuses the CSV value and NEW stores the generated value.
        // Persist it with every streamed case so a later batch timeout cannot
        // leave events/relationships available while the report is lost.
        ObjectNode libraryDocument = framework.deepCopy();
        JsonNode analysisTexts = result.path("suspiciousReport").path("analysisTexts");
        if (analysisTexts.isObject() && !analysisTexts.isEmpty()) {
            libraryDocument.set("analysis_texts", analysisTexts.deepCopy());
        }
        String frameworkJson = objectMapper.writeValueAsString(libraryDocument);
        jdbc.update("""
            INSERT INTO structured_case_library
            (case_id,bank_code,scenario_code,recognition_mode,case_document,content_sha256,status)
            VALUES (?,?,?,?,?::jsonb,?,'ACTIVE')
            ON CONFLICT (case_id) DO UPDATE SET
              scenario_code=EXCLUDED.scenario_code,
              recognition_mode=EXCLUDED.recognition_mode,
              case_document=EXCLUDED.case_document,
              content_sha256=EXCLUDED.content_sha256,
              status='ACTIVE',updated_at=CURRENT_TIMESTAMP
            """, caseId, bank, value(job.get("scenario_code"), "AML"),
                recognitionMode,
                frameworkJson, sha256(frameworkJson));
        jdbc.update("""
            INSERT INTO case_processing_pool
            (case_id,job_id,bank_code,scenario_code,recognition_mode,processing_stage,
             source_payload,suspicious_report,framework_result,graph_snapshot,
             recommended_risk_level,risk_score,risk_breakdown,stage_completed_at,approved_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,
                    ?,NULL,NULL,CURRENT_TIMESTAMP,
                    CASE WHEN ?='APPROVED' THEN CURRENT_TIMESTAMP ELSE NULL END)
            ON CONFLICT (case_id) DO UPDATE SET
              job_id=EXCLUDED.job_id,scenario_code=EXCLUDED.scenario_code,
              recognition_mode=EXCLUDED.recognition_mode,processing_stage=EXCLUDED.processing_stage,
              source_payload=EXCLUDED.source_payload,suspicious_report=EXCLUDED.suspicious_report,
              framework_result=EXCLUDED.framework_result,graph_snapshot=EXCLUDED.graph_snapshot,
              recommended_risk_level=EXCLUDED.recommended_risk_level,
              risk_score=NULL,risk_breakdown=NULL,
              approved_at=EXCLUDED.approved_at,
              last_error=NULL,stage_completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
            """, caseId, job.get("job_id"), bank, value(job.get("scenario_code"), "AML"),
                recognitionMode, caseStatus,
                objectMapper.writeValueAsString(rawRecord.isObject() ? rawRecord : objectMapper.createObjectNode()),
                objectMapper.writeValueAsString(result.path("suspiciousReport")), frameworkJson,
                objectMapper.writeValueAsString(snapshot), riskLevel, caseStatus);
        recordCaseJobRelation(caseId, job);
        tuGraphWriter.writeFrameworkCase(caseId, bank,
                ((Number) job.get("workspace_id")).longValue(), framework);
        return true;
    }

    private boolean persistUploadedStructuredCase(
            Map<String, Object> job, JsonNode result, JsonNode rawRecord) throws Exception {
        JsonNode basic = rawRecord.path("basic_info");
        String caseId = truncate(value(result.path("caseId").asText(), basic.path("case_id").asText()), 64);
        if (caseId == null || caseId.isBlank()) throw new IllegalStateException("上传案例缺少 caseId");
        String bank = String.valueOf(job.get("bank_code"));
        List<String> existingBanks = jdbc.queryForList(
                "SELECT bank_code FROM cf_risk_case WHERE case_id=?", String.class, caseId);
        if (!existingBanks.isEmpty() && !bank.equals(existingBanks.get(0))) {
            throw new IllegalStateException("案例 " + caseId + " 已属于其他银行，不能覆盖入库");
        }
        String caseName = truncate(normalizeStructuredCaseName(value(result.path("caseName").asText(),
                basic.path("case_name").asText(caseId))), 240);
        String description = truncate(basic.path("case_description").asText(), 100_000);
        jdbc.update("""
            INSERT INTO cf_risk_case
            (case_id,case_version,case_name,source_case_no,description,bank_code,workspace_id,
             scenario_code,case_source,case_type,case_status,risk_score,risk_level,subject_count,
             transaction_count,owner_analyst,struct_decision,decision_conflict,deleted)
            VALUES (?,1,?,?,?,?,?,?,'STRUCTURED_CASE','STRUCTURED_CASE','PENDING_REPORT',NULL,NULL,?,?,
                    'analysis-worker','PENDING',false,false)
            ON CONFLICT (case_id) DO UPDATE SET
              case_name=EXCLUDED.case_name,description=EXCLUDED.description,
              workspace_id=EXCLUDED.workspace_id,scenario_code=EXCLUDED.scenario_code,
              case_status='PENDING_REPORT',risk_score=NULL,risk_level=NULL,
              subject_count=EXCLUDED.subject_count,transaction_count=EXCLUDED.transaction_count,
              updated_at=CURRENT_TIMESTAMP,deleted=false
            """, caseId, caseName, caseId, description, bank, job.get("workspace_id"),
                value(job.get("scenario_code"), "AML"), rawRecord.path("customers").size(),
                rawRecord.path("transaction_features").path("transactions").size());
        String rawJson = objectMapper.writeValueAsString(rawRecord);
        jdbc.update("""
            INSERT INTO case_processing_pool
            (case_id,job_id,bank_code,scenario_code,recognition_mode,processing_stage,source_payload,stage_completed_at)
            VALUES (?,?,?,?,?,'PENDING_REPORT',?::jsonb,CURRENT_TIMESTAMP)
            ON CONFLICT (case_id) DO UPDATE SET
              job_id=EXCLUDED.job_id,scenario_code=EXCLUDED.scenario_code,
              recognition_mode=EXCLUDED.recognition_mode,processing_stage='PENDING_REPORT',
              source_payload=EXCLUDED.source_payload,suspicious_report=NULL,framework_result=NULL,
              graph_snapshot=NULL,similarity_result=NULL,risk_score=NULL,recommended_risk_level=NULL,
              risk_breakdown=NULL,last_error=NULL,stage_completed_at=CURRENT_TIMESTAMP,
              updated_at=CURRENT_TIMESTAMP
            """, caseId, job.get("job_id"), bank, value(job.get("scenario_code"), "AML"),
                value(result.path("recognitionMode").asText(), "NEW"), rawJson);
        recordCaseJobRelation(caseId, job);
        return true;
    }

    private void persistXiAnEvents(String caseId, String bank, JsonNode events,
                                   BigDecimal riskScore, String riskLevel) throws Exception {
        if (!events.isArray()) return;
        jdbc.update("""
            UPDATE cf_risk_event SET deleted=true,updated_at=CURRENT_TIMESTAMP
             WHERE case_id=? AND rule_name='XI_AN_CASE_FRAMEWORK_EXTRACTION' AND deleted=false
            """, caseId);
        int index = 0;
        for (JsonNode event : events) {
            String rawId = value(event.path("event_id").asText(), "EVENT-" + (++index));
            String eventId = "EVT-XI-" + sha256(caseId + "\u0000" + rawId).substring(0, 40).toUpperCase();
            jdbc.update("""
                INSERT INTO cf_risk_event
                (event_id,case_id,case_version,bank_code,event_name,event_type,event_standard_code,
                 confidence,evidence_refs,risk_score,risk_level,rule_name,subject_count)
                VALUES (?,?,1,?,?,?,?,?,?::jsonb,?,?,?,?)
                ON CONFLICT (event_id) DO UPDATE SET
                  bank_code=EXCLUDED.bank_code,
                  event_name=EXCLUDED.event_name,
                  event_type=EXCLUDED.event_type,
                  event_standard_code=EXCLUDED.event_standard_code,
                  confidence=EXCLUDED.confidence,
                  evidence_refs=EXCLUDED.evidence_refs,
                  risk_score=EXCLUDED.risk_score,
                  risk_level=EXCLUDED.risk_level,
                  rule_name=EXCLUDED.rule_name,
                  subject_count=EXCLUDED.subject_count,
                  deleted=false,
                  updated_at=CURRENT_TIMESTAMP
                """, eventId, caseId, bank,
                    truncate(value(event.path("event_name").asText(), rawId), 200),
                    truncate(event.path("event_type").asText(), 50),
                    truncate(eventStandardCode(event), 64),
                    riskScore, objectMapper.writeValueAsString(event), riskScore, riskLevel,
                    "XI_AN_CASE_FRAMEWORK_EXTRACTION", 1);
        }
    }

    private String jsonScalarOrArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> { if (!item.asText("").isBlank()) values.add(item.asText()); });
            return String.join("；", values);
        }
        return node.asText(null);
    }

    private String eventStandardCode(JsonNode event) {
        String direct = value(event.path("event_standard_code").asText(),
                event.path("event_type_id").asText());
        if (direct != null && !direct.isBlank()) return direct;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("知识库事件类型：(ET\\d+)")
                .matcher(event.path("recognition_rule").asText(""));
        return matcher.find() ? matcher.group(1) : null;
    }

    private Map<String, Object> buildTextCase(Map<String, Object> job) throws Exception {
        JsonNode input = objectMapper.readTree(value(job.get("input_params"), "{}"));
        String text = input.path("originalText").asText("").trim();
        long documentId = input.path("documentId").asLong(0);
        if (text.isEmpty() && documentId > 0 && job.get("batch_id") != null) {
            List<String> documentTexts = jdbc.queryForList("""
                SELECT extracted_text FROM unstructured_document
                WHERE id=? AND batch_id=? AND validation_status='VALID'
                """, String.class, documentId, ((Number) job.get("batch_id")).longValue());
            if (!documentTexts.isEmpty()) text = value(documentTexts.get(0), "").trim();
        }
        if (text.isEmpty() && job.get("batch_id") != null) {
            throw new IllegalStateException("非结构化识别任务缺少 documentId；批次必须展开为一个文档一个任务");
        }
        if (text.isEmpty()) throw new IllegalStateException("非结构化案例识别必须提供原始文本");
        String bank = String.valueOf(job.get("bank_code"));
        long workspace = ((Number) job.get("workspace_id")).longValue();
        String scenario = value(job.get("scenario_code"), "AML");
        String hash = sha256(text);
        String caseId = newId("CASE-T");
        JsonNode workerWorkspace = runUnstructuredWorker(
                String.valueOf(job.get("job_id")), caseId, bank,
                value(job.get("scenario_code"), "AML"), text);
        JsonNode graphOutput = workerWorkspace.path("final").path("output");
        if (!graphOutput.has("nodes")) graphOutput = workerWorkspace;
        JsonNode workerEvents = graphOutput.path("nodes").path("events");
        if (!workerEvents.isArray() || workerEvents.isEmpty()) {
            throw new IllegalStateException("worker 未产出 phase-03-event-nodes 事件");
        }
        String signalId = newId("SIG-TXT");
        JsonNode workerCase = graphOutput.path("nodes").path("cases").path(0);
        CaseNamingPolicy.Result caseIdentity = CaseNamingPolicy.normalize(
                workerCase.path("name").asText(), text, graphOutput);
        String caseName = truncate(caseIdentity.caseName(), 128);
        String sourceCaseNo = truncate(value(workerCase.path("case_no").asText(),
                caseIdentity.sourceCaseNo()), 64);
        String caseDescription = truncate(value(workerCase.path("raw_text").asText(), text), 4000);
        String businessDomain = defaultText(workerOverviewField(workerCase,
                "business_domain", "businessDomain", "业务领域"), "01-反洗钱");
        String businessCaseType = workerOverviewField(workerCase,
                "business_case_type", "businessCaseType", "type", "案例类型");
        String reportingDirection = normalizeFrameworkOption("reportingDirection",
                workerOverviewField(workerCase, "reporting_direction", "reportingDirection", "报送方向"));
        if (reportingDirection == null) reportingDirection = inferReportingDirection(text);
        String triggerPoint = normalizeFrameworkOption("triggerPoint",
                workerOverviewField(workerCase, "trigger_point", "triggerPoint", "案例触发点"));
        if (triggerPoint == null) triggerPoint = inferTriggerPoint(text);
        String urgencyLevel = normalizeFrameworkOption("urgencyLevel",
                workerOverviewField(workerCase, "urgency_level", "urgencyLevel", "紧急程度"));
        if (urgencyLevel == null && text.matches("(?s).*(特别紧急|紧急报送|立即处置).*")) {
            urgencyLevel = "02-特别紧急";
        }
        String reportedAt = normalizeReportedAt(workerOverviewField(workerCase,
                "reported_at", "reportedAt", "case_report_time", "案例上报时间"));
        if (reportedAt.isBlank()) reportedAt = inferReportedAt(text);
        String businessCaseStatus = normalizeFrameworkOption("businessCaseStatus",
                workerOverviewField(workerCase, "business_case_status", "businessCaseStatus", "status", "案例状态"));
        if (businessCaseStatus == null) businessCaseStatus = inferBusinessCaseStatus(text);
        String businessRiskLevel = normalizeFrameworkOption("businessRiskLevel",
                workerOverviewField(workerCase, "business_risk_level", "businessRiskLevel", "risk_level", "风险等级"));
        if (businessRiskLevel == null) businessRiskLevel = inferBusinessRiskLevel(text);
        String suspectedCrimeType = normalizeFrameworkOption("suspectedCrimeType",
                workerOverviewField(workerCase,
                        "suspected_crime_type", "suspectedCrimeType", "疑似涉罪类型"));
        if (suspectedCrimeType == null) suspectedCrimeType = inferSuspectedCrimeType(text);
        String featureCode = normalizeFrameworkOptions("suspiciousTransactionFeatureCode",
                workerOverviewField(workerCase,
                        "suspicious_transaction_feature_code", "suspiciousTransactionFeatureCode",
                        "suspected_transaction_feature_codes",
                        "可疑交易特征代码"));
        if (featureCode == null) featureCode = inferTransactionFeatureCodes(text);
        String disposalMeasure = workerOverviewField(workerCase,
                "disposal_measure", "disposalMeasure", "disposal_measures", "处置措施");
        if (disposalMeasure == null) disposalMeasure = inferDisposalMeasure(text);
        BigDecimal confidence = new BigDecimal("0.850000");
        jdbc.update("""
                INSERT INTO risk_signal
                (signal_id,bank_code,workspace_id,scenario_code,pipeline_version,signal_type,
                 source_ref_type,source_ref_id,algorithm_id,algorithm_version,score,decision,
                 reason_codes,recommended_action,input_data_hash,status,created_by)
                VALUES (?,?,?,?,'embedded-worker-v1','TEXT','DOCUMENT',?,'TEXT_ENTITY_EVENT_EXTRACTOR',
                        '1.0',?,'SUSPECTED',ARRAY['TEXT_ILLEGAL_BEHAVIOR'],'TEXT_CASE_IDENTIFICATION',
                        ?,'MERGED','analysis-worker')
                """, signalId, bank, workspace, scenario, hash.substring(0, 24), confidence, hash);
        jdbc.update("""
                INSERT INTO text_risk_signal
                (signal_id,text_case_id,document_hash,extraction_confidence,llm_model_version,
                 entity_extractions,event_extractions,evidence_offsets,original_text_fragment)
                VALUES (?,?,?,?,'deterministic-v1','[]'::jsonb,'[]'::jsonb,'[]'::jsonb,?)
                """, signalId, caseId, hash, confidence, text.substring(0, Math.min(text.length(), 1000)));
        jdbc.update("""
                INSERT INTO cf_risk_case
                (case_id,case_version,case_name,source_case_no,description,bank_code,workspace_id,scenario_code,case_source,case_type,
                 case_status,risk_score,risk_level,text_decision,decision_conflict,deleted,
                 business_domain,business_case_type,reporting_direction,trigger_point,urgency_level,reported_at,
                 business_case_status,business_risk_level,suspected_crime_type,
                 suspicious_transaction_feature_code,disposal_measure)
                VALUES (?,1,?,?,?,?,?,?,'TEXT_CASE','UNSTRUCTURED_CASE','DRAFT',?,'HIGH','SUSPECTED',false,false,
                        ?,?,?,?,?,NULLIF(?,'')::timestamptz,?,?,?,?,?)
                """, caseId, caseName, sourceCaseNo, caseDescription, bank, workspace, scenario, confidence,
                businessDomain, businessCaseType, reportingDirection, triggerPoint, urgencyLevel, reportedAt,
                businessCaseStatus, businessRiskLevel, suspectedCrimeType, featureCode, disposalMeasure);
        jdbc.update("""
                INSERT INTO case_signal_rel(case_id,case_version,signal_id,bank_code,signal_role)
                VALUES (?,1,?,?,'PRIMARY')
                """, caseId, signalId, bank);
        jdbc.update("""
                INSERT INTO risk_signal_analysis_job_rel(signal_id,job_id,bank_code)
                VALUES (?,?,?) ON CONFLICT (signal_id,job_id) DO NOTHING
                """, signalId, job.get("job_id"), bank);
        int workerEventCount = persistWorkerEvents(caseId, bank, workerEvents, confidence);
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        result.put("caseId", caseId); result.put("signalId", signalId); result.put("documentHash", hash);
        result.put("worker", "DataGraph-IllegalCaseExtraction-TuGraph");
        result.put("workerEventCount", workerEventCount);
        result.put("workerResult", workerWorkspace);
        recordSchemaFeedback(job, workerWorkspace);
        try {
            eventSemanticEnrichmentService.enrichCase(caseId);
            result.putAll(tuGraphWriter.writeTextCase(caseId, bank, workspace, text, graphOutput));
            result.put("graphStatus", "SUCCEEDED");
        } catch (Exception graphError) {
            result.put("graphStatus", "PENDING");
            result.put("graphError", graphError.getMessage());
        }
        recordCaseJobRelation(caseId, job);
        refreshMatterExplanation(caseId, result);
        return result;
    }

    private void recordCaseJobRelation(String caseId, Map<String, Object> job) {
        jdbc.update("""
            INSERT INTO case_analysis_job_rel(case_id,job_id,batch_id,bank_code,job_type)
            VALUES (?,?,?,?,?) ON CONFLICT (case_id,job_id) DO NOTHING
            """, caseId, job.get("job_id"), job.get("batch_id"), job.get("bank_code"), job.get("job_type"));
    }

    private void refreshMatterExplanation(String caseId, Map<String, Object> result) {
        if (matterExplanationService == null) return;
        try {
            Map<String, Object> summary = matterExplanationService.refresh(caseId);
            if (result != null) {
                result.put("matterExplanationStatus", "SUCCEEDED");
                result.put("matterExplanation", summary);
            }
        } catch (Exception ex) {
            // Matter explanations are a derived, replayable projection. A temporary
            // analytics outage must not roll back an otherwise valid case.
            if (result != null) {
                result.put("matterExplanationStatus", "PENDING");
                result.put("matterExplanationError", truncate(ex.getMessage(), 500));
            }
        }
    }

    /**
     * Recognition may discover fields that were not present in the upload header.
     * Persist them as a review candidate; never mutate a published schema directly.
     */
    private void recordSchemaFeedback(Map<String, Object> job, JsonNode workerResult) {
        if (job.get("batch_id") == null || workerResult == null || workerResult.isMissingNode()) return;
        long batchId = ((Number) job.get("batch_id")).longValue();
        String type = String.valueOf(job.get("job_type"));
        List<Long> schemaIds = "UNSTRUCTURED".equals(type)
                ? jdbc.query("SELECT schema_id FROM unstructured_ingest_batch WHERE id=? AND schema_id IS NOT NULL",
                    (rs, n) -> rs.getLong(1), batchId)
                : jdbc.query("SELECT schema_id FROM risk_ingest_batch WHERE id=? AND schema_id IS NOT NULL",
                    (rs, n) -> rs.getLong(1), batchId);
        if (schemaIds.isEmpty()) return;
        Set<String> paths = new LinkedHashSet<>();
        collectJsonPaths(workerResult, "", paths, 0);
        try {
            String definition = objectMapper.writeValueAsString(Map.of(
                    "source", "WORKER_RECOGNITION",
                    "jobType", type,
                    "fieldPaths", paths));
            jdbc.update("""
                INSERT INTO schema_recognition_feedback
                  (job_id,batch_id,schema_id,bank_code,job_type,discovered_definition,status)
                VALUES (?,?,?,?,?,?::jsonb,'PENDING')
                ON CONFLICT (job_id) DO UPDATE SET
                  discovered_definition=EXCLUDED.discovered_definition,
                  status=CASE WHEN schema_recognition_feedback.status='PENDING' THEN 'PENDING' ELSE schema_recognition_feedback.status END
                """, job.get("job_id"), batchId, schemaIds.get(0), job.get("bank_code"), type, definition);
        } catch (Exception ex) {
            throw new IllegalStateException("识别结果已生成，但Schema回填候选保存失败: " + ex.getMessage(), ex);
        }
    }

    private void collectJsonPaths(JsonNode node, String prefix, Set<String> paths, int depth) {
        if (node == null || depth > 8 || paths.size() >= 1000) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                paths.add(path);
                collectJsonPaths(entry.getValue(), path, paths, depth + 1);
            });
        } else if (node.isArray() && !node.isEmpty()) {
            collectJsonPaths(node.get(0), prefix + "[]", paths, depth + 1);
        }
    }

    private JsonNode runUnstructuredWorker(
            String jobId, String caseId, String bank, String scenario, String text) throws Exception {
        Path executionRoot = workerRootForAssembly("UNSTRUCTURED", scenario, bank, unstructuredWorkerRoot);
        if (!Files.exists(executionRoot.resolve("src/main.py"))) {
            throw new IllegalStateException("非结构化 worker 未部署: " + unstructuredWorkerRoot);
        }
        Path input = Files.createTempFile("bankgraph-" + caseId + "-", ".txt");
        Files.writeString(input, text, StandardCharsets.UTF_8);
        Map<String,Object> assembly = activeBatchAssembly("UNSTRUCTURED", scenario, bank,
                "UNSTRUCTURED_CASE_GRAPH_EXTRACTION", "1.0.0");
        String executionMode = unstructuredExecutionMode(assembly);
        String executionId = beginBatchAlgorithmExecution(jobId, bank, scenario, assembly,
                executionRoot.resolve("src/main.py").toString(), 1, sha256(text));
        long started = System.nanoTime();
        // The Java service is the single authoritative TuGraph writer. Let the
        // Python worker produce canonical graph JSON, but do not let it also
        // persist content-hash UIDs that collide across repeated recognition.
        try {
            Process process = new ProcessBuilder(pythonCommand, "-u", "src/main.py", "--input", input.toString(),
                    "--case-id", caseId, "--runs-dir", "runs", "--no-tugraph",
                    "--execution-mode", executionMode)
                    .directory(executionRoot.toFile()).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    updateUnstructuredWorkerProgress(jobId, line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) throw new IllegalStateException("非结构化 worker 执行失败: "
                    + output.substring(Math.max(0, output.length() - 2000)));
            Path workspace = executionRoot.resolve("runs").resolve(caseId).resolve("workspace/workspace.json");
            if (!Files.exists(workspace)) throw new IllegalStateException("worker 未生成 workspace.json");
            JsonNode result = objectMapper.readTree(Files.readString(workspace, StandardCharsets.UTF_8));
            int resultCount = result.path("final").path("output").path("nodes").path("events").size();
            completeBatchAlgorithmExecution(executionId, "SUCCEEDED", resultCount, started, null);
            return result;
        } catch (Exception ex) {
            completeBatchAlgorithmExecution(executionId, "FAILED", 0, started, ex.getMessage());
            throw ex;
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private void updateUnstructuredWorkerProgress(String jobId, String line) {
        int marker = line.indexOf("phase-");
        if (marker < 0) return;
        int end = line.indexOf(' ', marker);
        String phase = (end < 0 ? line.substring(marker) : line.substring(marker, end)).trim();
        String stepType = switch (phase) {
            case "phase-00-canonical-text" -> "P00";
            case "phase-01-normalized-text" -> "P01";
            case "phase-02a-entity-nodes" -> "P02a";
            case "phase-03-event-nodes" -> "P03";
            case "phase-04-evidence-nodes" -> "P04";
            case "phase-05a-case-node" -> "P05a";
            case "phase-05b-case-merge" -> "P05b";
            case "phase-06-case-edges" -> "P06";
            case "phase-07-evidence-edges" -> "P07";
            case "phase-08-event-edges" -> "P08";
            case "phase-09-entity-edges" -> "P09";
            case "phase-10-final-validate" -> "P10";
            case "phase-11-graph-persist" -> "P11";
            default -> null;
        };
        if (stepType == null) return;
        List<Integer> orders = jdbc.queryForList(
                "SELECT step_order FROM analysis_job_step WHERE job_id=? AND step_type=?",
                Integer.class, jobId, stepType);
        if (orders.isEmpty() && "P09".equals(stepType)) {
            orders = jdbc.queryForList(
                    "SELECT step_order FROM analysis_job_step WHERE job_id=? AND step_type='P02b'",
                    Integer.class, jobId);
        }
        if (orders.isEmpty()) return;
        int order = orders.get(0);
        if (line.startsWith("[RUN") || line.startsWith("[SKIP")) {
            markStep(jobId, order, "RUNNING", "P11".equals(stepType) ? 70 : 25, null, null);
        } else if (line.startsWith("[OK") && !"P11".equals(stepType)) {
            markStep(jobId, order, "SUCCEEDED", 100,
                    "{\"workerPhase\":\"" + phase + "\"}", null);
        }
        refreshJob(jobId);
    }

    private Map<String,Object> activeBatchAssembly(
            String taskType, String scenario, String bank,
            String fallbackAlgorithmId, String fallbackVersion) {
        List<Map<String,Object>> configured = jdbc.queryForList("""
                SELECT a.algorithm_id,a.algorithm_version,a.worker_id,a.worker_version
                      ,a.parameters::text AS parameters_json
                FROM algorithm_assembly a
                JOIN algorithm_version_registry v
                  ON v.algorithm_id=a.algorithm_id AND v.algorithm_version=a.algorithm_version
                JOIN worker_registry w
                  ON w.worker_id=a.worker_id AND w.worker_version=a.worker_version
                WHERE a.task_type=? AND a.scenario_code=? AND a.status='ACTIVE'
                  AND w.status='ACTIVE' AND v.status IN ('ACTIVE','REGISTERED')
                  AND (a.bank_code=? OR a.bank_code IS NULL)
                ORDER BY CASE WHEN a.bank_code=? THEN 0 ELSE 1 END,a.priority DESC
                LIMIT 1
                """, taskType, scenario, bank, bank);
        if (!configured.isEmpty()) return configured.get(0);
        Map<String,Object> fallback = new java.util.LinkedHashMap<>();
        fallback.put("algorithm_id", fallbackAlgorithmId);
        fallback.put("algorithm_version", fallbackVersion);
        fallback.put("worker_id", taskType + "_WORKER");
        fallback.put("worker_version", fallbackVersion);
        fallback.put("parameters_json", "{}");
        return fallback;
    }

    private String unstructuredExecutionMode(Map<String,Object> assembly) {
        String fallback = "HYBRID_AUTO";
        try {
            JsonNode parameters = objectMapper.readTree(
                    String.valueOf(assembly.getOrDefault("parameters_json", "{}")));
            String mode = parameters.path("executionMode").asText(fallback).trim().toUpperCase();
            return switch (mode) {
                case "LEGACY_LLM", "HYBRID_AUTO", "RULE_ONLY" -> mode;
                default -> fallback;
            };
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String beginBatchAlgorithmExecution(
            String jobId, String bank, String scenario, Map<String,Object> assembly,
            String endpoint, int requestCount, String requestHash) {
        String executionId = "ALGEX-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO algorithm_execution_log
                (execution_id,job_id,bank_code,scenario_code,algorithm_id,algorithm_version,
                 endpoint,request_count,status,request_hash)
                VALUES (?,?,?,?,?,?,?,?, 'RUNNING',?)
                """, executionId, jobId, bank, scenario,
                String.valueOf(assembly.get("algorithm_id")),
                String.valueOf(assembly.get("algorithm_version")),
                endpoint, requestCount, requestHash);
        return executionId;
    }

    private void completeBatchAlgorithmExecution(
            String executionId, String status, int resultCount, long started, String error) {
        jdbc.update("""
                UPDATE algorithm_execution_log
                SET status=?,result_count=?,duration_ms=?,error_message=?,
                    completed_at=CURRENT_TIMESTAMP
                WHERE execution_id=?
                """, status, resultCount, (System.nanoTime() - started) / 1_000_000,
                error, executionId);
    }

    private Path workerRootForAssembly(String taskType, String scenario, String bank, Path fallbackRoot) {
        List<String> configured = jdbc.queryForList("""
                SELECT w.package_path
                FROM algorithm_assembly a
                JOIN worker_registry w
                  ON w.worker_id=a.worker_id AND w.worker_version=a.worker_version
                WHERE a.task_type=? AND a.scenario_code=? AND a.status='ACTIVE'
                  AND w.status='ACTIVE' AND COALESCE(w.package_path,'')<>''
                  AND (a.bank_code=? OR a.bank_code IS NULL)
                ORDER BY CASE WHEN a.bank_code=? THEN 0 ELSE 1 END,a.priority DESC
                LIMIT 1
                """, String.class, taskType, scenario, bank, bank);
        Path root = configured.isEmpty()
                ? fallbackRoot : Path.of(configured.get(0)).toAbsolutePath().normalize();
        if (bank != null && !bank.isBlank()) {
            Path scoped = root.resolve(bank).normalize();
            if (Files.exists(scoped.resolve("src/main.py"))) return scoped;
        }
        return root;
    }

    private int persistWorkerEvents(String caseId, String bank, JsonNode events, BigDecimal confidence) {
        int count = 0;
        for (JsonNode event : events) {
            String eventType = truncate(value(event.path("type").asText(), "99-其他"), 64);
            String eventName = truncate(resolveWorkerEventName(event, eventType), 64);
            EventDictionaryResolver.Resolution definition =
                    EventDictionaryResolver.resolve(eventType, eventName);
            jdbc.update("""
                INSERT INTO cf_risk_event
                (event_id,case_id,case_version,bank_code,event_name,event_type,event_standard_code,
                 event_frame_code,event_frame_version,definition_binding_status,definition_match_method,
                 definition_match_confidence,event_time,confidence,evidence_refs,risk_score,risk_level,
                 rule_name,subject_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'[]'::jsonb,?,?,?,?)
                """, "EVENT-" + UUID.randomUUID(), caseId, 1, bank,
                eventName, eventType, truncate(event.path("uid").asText(null), 64),
                definition.frameCode(), 1, "BOUND", definition.matchMethod(), definition.confidence(),
                null, confidence, confidence,
                riskLevel(confidence), "PYTHON_WORKER_PHASE_03_EVENT_NODES", 0);
            count++;
        }
        return count;
    }

    private String resolveWorkerEventName(JsonNode event, String eventType) {
        String supplied = event.path("name").asText("").trim();
        if (!supplied.isBlank() && !"UNNAMED_WORKER_EVENT".equalsIgnoreCase(supplied)) return supplied;
        String typeName = value(eventType, "其他事件").replaceFirst("^\\d{2}-", "");
        String text = String.join(" ",
                event.path("event_text").asText(""),
                event.path("description").asText(""),
                event.path("channel").asText(""));
        if (typeName.contains("收款") && text.matches("(?s).*(?:多笔|三笔|归集).*")) {
            return "多笔资金收取";
        }
        if ((typeName.contains("转账") || typeName.contains("付款"))
                && text.matches("(?s).*(?:分拆|分散).*转出.*")) {
            return text.contains("网银") ? "网银分拆资金转出" : "分拆资金转出";
        }
        return typeName.isBlank() ? "其他交易事件" : typeName;
    }

    private void markStep(String jobId, int order, String status, int progress, String result, String error) {
        jdbc.update("""
                UPDATE analysis_job_step SET status=?,progress=?,result_json=CAST(? AS JSONB),
                  error_message=?,started_at=COALESCE(started_at,CURRENT_TIMESTAMP),
                  completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE job_id=? AND step_order=?
                """, status, progress, result, error, status, jobId, order);
    }

    private void refreshJob(String jobId) {
        jdbc.update("""
                UPDATE analysis_job j SET
                  progress=(SELECT COALESCE(AVG(progress),0)::INT FROM analysis_job_step WHERE job_id=j.job_id),
                  current_step=(SELECT step_name FROM analysis_job_step WHERE job_id=j.job_id AND status='RUNNING' ORDER BY step_order LIMIT 1),
                  status=CASE WHEN NOT EXISTS (SELECT 1 FROM analysis_job_step WHERE job_id=j.job_id AND status<>'SUCCEEDED')
                              THEN 'SUCCEEDED' ELSE 'RUNNING' END,
                  completed_at=CASE WHEN NOT EXISTS (SELECT 1 FROM analysis_job_step WHERE job_id=j.job_id AND status<>'SUCCEEDED')
                                    THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE j.job_id=?
                """, jobId);
    }

    private String json(Object value) throws Exception { return objectMapper.writeValueAsString(value); }
    private String workerOverviewField(JsonNode workerCase, String... keys) {
        if (workerCase == null || workerCase.isMissingNode()) return null;
        List<JsonNode> sources = List.of(
                workerCase.path("case_overview"),
                workerCase.path("caseOverview"),
                workerCase.path("overview"),
                workerCase.path("properties"),
                workerCase);
        for (JsonNode source : sources) {
            if (!source.isObject()) continue;
            for (String key : keys) {
                JsonNode candidate = source.get(key);
                if (candidate != null && !candidate.isNull()) {
                    String text = candidate.isArray()
                            ? java.util.stream.StreamSupport.stream(candidate.spliterator(), false)
                                .map(item -> item.asText("").trim()).filter(item -> !item.isBlank())
                                .collect(java.util.stream.Collectors.joining("、"))
                            : candidate.asText("").trim();
                    if (!text.isBlank() && !"待补充".equals(text)) return truncate(text, 4000);
                }
            }
        }
        return null;
    }
    private String normalizeFrameworkOption(String fieldCode, String extracted) {
        if (extracted == null || extracted.isBlank()) return null;
        String candidate = extracted.trim();
        if ("reportingDirection".equals(fieldCode)) {
            if (candidate.contains("03-") || candidate.contains("公安机关")) {
                candidate = "03-中国反洗钱监测分析中心和当地公安机关";
            } else if (candidate.contains("02-") || candidate.contains("人民银行")) {
                candidate = "02-中国反洗钱监测分析中心和人民银行当地分支机构";
            } else if (candidate.contains("01-") || candidate.contains("反洗钱监测分析中心")) {
                candidate = "01-中国反洗钱监测分析中心";
            }
        }
        final String normalizedCandidate = candidate;
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT option_code,option_label,option_value
            FROM case_framework_option_metadata
            WHERE field_code=? AND status='ACTIVE' ORDER BY sort_order,id
            """, fieldCode);
        for (Map<String, Object> row : rows) {
            String code = value(row.get("option_code"), "");
            String label = value(row.get("option_label"), "");
            String optionValue = value(row.get("option_value"), "");
            if (normalizedCandidate.equals(optionValue)
                    || normalizedCandidate.equals(code) || normalizedCandidate.equals(label)) {
                return optionValue;
            }
        }
        List<String> semanticMatches = rows.stream()
                .filter(row -> {
                    String label = value(row.get("option_label"), "");
                    return label.length() >= 2
                            && (normalizedCandidate.contains(label) || label.contains(normalizedCandidate));
                })
                .map(row -> value(row.get("option_value"), ""))
                .distinct().toList();
        return semanticMatches.size() == 1 ? semanticMatches.get(0) : null;
    }
    private String normalizeFrameworkOptions(String fieldCode, String extracted) {
        if (extracted == null || extracted.isBlank()) return null;
        List<String> normalized = java.util.Arrays.stream(extracted.split("[、；;,，\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> normalizeFrameworkOption(fieldCode, value))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : String.join("；", normalized);
    }
    private String normalizeReportedAt(String extracted) {
        if (extracted == null || extracted.isBlank()) return "";
        String value = extracted.trim().replace('/', '-');
        try {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return value + "T00:00:00";
            return java.time.OffsetDateTime.parse(value).toString();
        } catch (Exception ignored) {
            try { return java.time.LocalDateTime.parse(value).toString(); }
            catch (Exception invalid) { return ""; }
        }
    }
    private String inferReportingDirection(String text) {
        String compact = value(text, "");
        if (compact.matches("(?s).*(报送|上报).{0,30}(公安机关|公安部门).*")) {
            return "03-中国反洗钱监测分析中心和当地公安机关";
        }
        if (compact.matches("(?s).*(报送|上报).{0,30}(人民银行|央行).*")) {
            return "02-中国反洗钱监测分析中心和人民银行当地分支机构";
        }
        if (compact.matches("(?s).*(报送|上报).{0,30}(反洗钱监测分析中心).*")) {
            return "01-中国反洗钱监测分析中心";
        }
        return null;
    }
    private String inferTriggerPoint(String text) {
        String compact = value(text, "");
        if (compact.matches("(?s).*(公安|纪检|安全部门|执法部门).{0,25}(指令|冻结|协查).*")
                || compact.matches("(?s).*(指令|冻结|协查).{0,25}(公安|纪检|安全部门|执法部门).*")) {
            return "02-执法部门指令（公安、纪检、安全等部门的境内冻结、协查等）";
        }
        if (compact.matches("(?s).*(央行|人民银行|证监会|交易所|监管部门).{0,25}(指令|警示|协查).*")
                || compact.matches("(?s).*(指令|警示|协查).{0,25}(央行|人民银行|证监会|交易所|监管部门).*")) {
            return "03-监管部门指令（如央行、证监会、交易所等部门的警示或协查等）";
        }
        if (compact.matches("(?s).*(模型筛选|系统筛选|规则命中).*")) return "01-模型筛选";
        if (compact.matches("(?s).*(社会舆情|媒体报道|网络舆情).*")) return "05-社会舆情";
        if (compact.matches("(?s).*(从业人员|工作人员).{0,20}(发现|报告).*(异常).*")) {
            return "06-金融机构从业人员发现的身份、行为等异常状况";
        }
        return null;
    }
    private String inferBusinessCaseStatus(String text) {
        String compact = value(text, "");
        if (compact.matches("(?s).*(已判决|法院判决|判处).*")) return "已判决";
        if (compact.matches("(?s).*(已立案|立案侦查).*")) return "已立案";
        if (compact.matches("(?s).*(已排除|排除可疑).*")) return "已排除";
        if (compact.matches("(?s).*(重点核查).*")) return "重点核查";
        return "初步可疑";
    }
    private String inferBusinessRiskLevel(String text) {
        String compact = value(text, "");
        if (compact.matches("(?s).*(重点可疑|重大风险|犯罪网络|资金转移网络|团伙|涉案).*")) {
            return "重点可疑";
        }
        return "一般可疑";
    }
    private String inferSuspectedCrimeType(String text) {
        String compact = value(text, "");
        if (compact.matches("(?s).*(集资诈骗|非法集资).*")) return "0701-涉嫌集资诈骗的可疑交易行为";
        return null;
    }
    private String inferTransactionFeatureCodes(String text) {
        String compact = value(text, "");
        List<String> codes = new ArrayList<>();
        if (compact.matches("(?s).*((行为代码|代码)[：: ]*2002|涉赌组织.{0,30}(转移资金|洗钱)).*")) {
            codes.add("2002-涉赌组织经营赌博或为其转移资金");
        }
        if (compact.matches("(?s).*(快进快出).{0,25}(不留余额|极少余额).*")) {
            codes.add("1002-短期内对私客户快进快出不留余额");
        }
        if (compact.matches("(?s).*(非法集资|集资诈骗).*")) codes.add("1001-疑似非法集资");
        return codes.isEmpty() ? null : String.join("；", codes);
    }
    private String inferDisposalMeasure(String text) {
        String compact = value(text, "");
        List<String> measures = new ArrayList<>();
        if (compact.matches("(?s).*(已冻结|实施冻结|采取冻结).*")) measures.add("冻结");
        if (compact.matches("(?s).*(已止付|实施止付|采取止付).*")) measures.add("止付");
        if (compact.matches("(?s).*(已报送|完成报送).*")) measures.add("报送可疑交易报告");
        return measures.isEmpty() ? null : String.join("；", measures);
    }
    private String inferReportedAt(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?:上报时间|报送时间|上报日期|报送日期)[：:\\s]*(\\d{4}[-年/.]\\d{1,2}[-月/.]\\d{1,2})")
                .matcher(value(text, ""));
        if (!matcher.find()) return "";
        String normalized = matcher.group(1).replace('年', '-').replace('月', '-')
                .replace("/", "-").replace(".", "-").replace("日", "");
        String[] parts = normalized.split("-");
        if (parts.length != 3) return "";
        return String.format("%s-%02d-%02dT00:00:00", parts[0],
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private String normalizeStructuredCaseName(String value) {
        String normalized = defaultText(value, "未命名案例").trim();
        return normalized.replaceFirst("^.*?可疑案例[0-9０-９]+\\s*[—–-]+\\s*", "")
                .replaceFirst("^[\\s—–-]+", "");
    }
    private String normalizeStructuredBusinessDomain(String extracted, String scenario) {
        String normalized = defaultText(extracted, "").trim();
        if ("FRAUD".equalsIgnoreCase(scenario) || normalized.contains("欺诈")) return "02-反欺诈";
        return "01-反洗钱";
    }
    private String structuredCaseName(JsonNode component, String scenario) {
        String subject = "";
        JsonNode entities = component.path("accountEntities");
        if (entities.isArray()) {
            for (JsonNode entity : entities) {
                String candidate = entity.path("entityName").asText("").trim();
                if (!candidate.isBlank() && !candidate.matches("(?i)unknown|n/?a|none")) {
                    subject = candidate;
                    break;
                }
            }
        }
        if (subject.isBlank()) subject = "核心账户";
        long transactionCount = component.path("transactionCount").asLong();
        long accountCount = component.path("accountCount").asLong();
        String behavior = accountCount >= 4 ? "多账户关联资金流转"
                : transactionCount >= 3 ? "连续资金转移" : "关联转账";
        String scene = "FRAUD".equalsIgnoreCase(scenario) ? "反欺诈" : "反洗钱";
        return truncate(subject + scene + behavior + "案", 128);
    }
    private String value(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
    private java.sql.Timestamp parseStructuredEventTime(String value) {
        try {
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(value,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")));
        } catch (Exception ignored) {
            return java.sql.Timestamp.from(java.time.Instant.now());
        }
    }
    private String newId(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private String riskLevel(BigDecimal score) {
        if (score.compareTo(new BigDecimal("0.8")) >= 0) return "CRITICAL";
        if (score.compareTo(new BigDecimal("0.6")) >= 0) return "HIGH";
        if (score.compareTo(new BigDecimal("0.3")) >= 0) return "MEDIUM";
        return "LOW";
    }
    private String businessRiskLevel(BigDecimal score) {
        return score.compareTo(new BigDecimal("0.6")) >= 0 ? "重点可疑" : "一般可疑";
    }
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
