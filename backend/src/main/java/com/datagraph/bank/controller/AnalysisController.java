package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.config.OpenApiConfig;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.AnalysisWorkerService;
import com.datagraph.bank.service.AnalysisJobDeletionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long STRUCTURED_CASE_UPLOAD_LIMIT = 100L * 1024 * 1024;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;
    private final AnalysisWorkerService workerService;
    private final AnalysisJobDeletionService deletionService;
    private final Path unstructuredWorkerRoot;
    private final Path structuredCaseWorkerRoot;
    private final Path antiFraudCaseWorkerRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisController(JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                              AnalysisWorkerService workerService,
                              AnalysisJobDeletionService deletionService,
                              @Value("${worker.unstructured.root:./worker/unstructured}") String unstructuredWorkerRoot,
                              @Value("${worker.structured-case.root:../workers/structured-case-identification}") String structuredCaseWorkerRoot,
                              @Value("${worker.anti-fraud-case.root:../workers/anti-fraud-case-identification}") String antiFraudCaseWorkerRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.workerService = workerService;
        this.deletionService = deletionService;
        this.unstructuredWorkerRoot = Path.of(unstructuredWorkerRoot).toAbsolutePath().normalize();
        this.structuredCaseWorkerRoot = resolveStructuredCaseWorkerRoot(structuredCaseWorkerRoot);
        this.antiFraudCaseWorkerRoot = resolveWorkerRoot(
                antiFraudCaseWorkerRoot, "workers/anti-fraud-case-identification");
    }

    AnalysisController(JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                       AnalysisWorkerService workerService,
                       AnalysisJobDeletionService deletionService,
                       String unstructuredWorkerRoot) {
        this(jdbcTemplate, currentUser, workerService, deletionService,
                unstructuredWorkerRoot, "../workers/structured-case-identification",
                "../workers/anti-fraud-case-identification");
    }

    AnalysisController(JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                       AnalysisWorkerService workerService,
                       AnalysisJobDeletionService deletionService,
                       String unstructuredWorkerRoot,
                       String structuredCaseWorkerRoot) {
        this(jdbcTemplate, currentUser, workerService, deletionService,
                unstructuredWorkerRoot, structuredCaseWorkerRoot,
                Path.of(structuredCaseWorkerRoot).resolveSibling(
                        "anti-fraud-case-identification").toString());
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

    @GetMapping("/worker-config")
    public CommonResult<String> workerConfig(@RequestParam(defaultValue = "UNSTRUCTURED") String jobType) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可编辑 worker 配置");
        Path root = workerRootForUser(jobType);
        Path env = root.resolve(".env").normalize();
        if (!env.startsWith(root) || !Files.exists(env)) return CommonResult.error(404, "worker 配置不存在");
        return CommonResult.success(Files.readString(env));
    }

    @PutMapping("/worker-config")
    public CommonResult<Void> updateWorkerConfig(@RequestParam(defaultValue = "UNSTRUCTURED") String jobType,
                                                  @RequestBody Map<String,String> request) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可编辑 worker 配置");
        String content = request.getOrDefault("content", "");
        if (content.length() > 10000 || content.contains("\u0000")) return CommonResult.error(400, "配置内容非法");
        Path root = workerRootForUser(jobType);
        Path env = root.resolve(".env").normalize();
        if (!env.startsWith(root)) return CommonResult.error(400, "非法路径");
        Files.writeString(env, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return CommonResult.success(null);
    }

    @GetMapping("/worker-files")
    public CommonResult<List<String>> workerFiles(@RequestParam(defaultValue = "UNSTRUCTURED") String jobType) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可编辑 worker");
        Path root = workerRootForUser(jobType);
        Path sourceRoot = workerSourceRoot(jobType, root);
        try (var stream = Files.walk(sourceRoot)) {
            return CommonResult.success(stream.filter(p -> p.toString().endsWith(".py"))
                    .map(p -> root.relativize(p).toString().replace('\\','/')).sorted().toList());
        }
    }

    @GetMapping("/worker-file")
    public CommonResult<String> workerFile(@RequestParam String path,
                                            @RequestParam(defaultValue = "UNSTRUCTURED") String jobType) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可编辑 worker");
        Path root = workerRootForUser(jobType);
        Path sourceRoot = workerSourceRoot(jobType, root);
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(sourceRoot) || !Files.exists(file) || !file.toString().endsWith(".py")) return CommonResult.error(400, "非法 worker 文件");
        return CommonResult.success(Files.readString(file));
    }

    @PutMapping("/worker-file")
    public CommonResult<Void> updateWorkerFile(@RequestParam(defaultValue = "UNSTRUCTURED") String jobType,
                                                @RequestBody Map<String,String> request) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可编辑 worker");
        Path root = workerRootForUser(jobType);
        Path sourceRoot = workerSourceRoot(jobType, root);
        Path file = root.resolve(request.getOrDefault("path","")).normalize();
        String content = request.getOrDefault("content","");
        if (!file.startsWith(sourceRoot) || !file.toString().endsWith(".py") || content.length() > 500000) return CommonResult.error(400, "非法 worker 文件或内容过大");
        Files.writeString(file, content, StandardOpenOption.TRUNCATE_EXISTING);
        return CommonResult.success(null);
    }

    @PostMapping(value = "/worker-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<Map<String, Object>> uploadWorker(
            @RequestParam(defaultValue = "UNSTRUCTURED") String jobType,
            @RequestPart("file") MultipartFile upload) throws Exception {
        if (!isWorkerEditor()) return CommonResult.error(403, "仅超级管理员或本行管理员可上传 worker");
        String name = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".zip") || upload.isEmpty() || upload.getSize() > 200L * 1024 * 1024) {
            return CommonResult.error(400, "请上传不超过 200MB 的 zip 文件");
        }
        Path root = workerRootForUpload(jobType);
        Files.createDirectories(root);
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(upload.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || entry.getName().contains("..")) {
                    return CommonResult.error(400, "zip 中包含非法路径");
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return CommonResult.success(Map.of("files", count, "root", root.toString()));
    }

    private boolean isWorkerEditor() {
        String role = currentUser.principal().roleCode();
        return "sadmin".equalsIgnoreCase(role) || "badmin".equalsIgnoreCase(role);
    }

    private Path workerRootForUser(String jobType) {
        Path root = workerRoot(jobType);
        if ("STRUCTURED".equalsIgnoreCase(jobType) || "ANTI_FRAUD".equalsIgnoreCase(jobType)) return root;
        String bank = currentUser.principal().bankCode();
        if (bank == null || bank.isBlank()) return root;
        Path scoped = root.resolve(bank).normalize();
        return Files.exists(scoped.resolve("src")) ? scoped : root;
    }

    private Path workerRootForUpload(String jobType) {
        Path root = workerRoot(jobType);
        String bank = currentUser.principal().bankCode();
        if (bank == null || bank.isBlank()) return root;
        return root.resolve(bank).normalize();
    }

    private Path workerRoot(String jobType) {
        if ("STRUCTURED".equalsIgnoreCase(jobType)) return structuredCaseWorkerRoot;
        if ("ANTI_FRAUD".equalsIgnoreCase(jobType)) return antiFraudCaseWorkerRoot;
        if ("UNSTRUCTURED".equalsIgnoreCase(jobType)) return unstructuredWorkerRoot;
        throw new IllegalArgumentException("旧交易识别/聚类 Worker 已退役");
    }

    private Path workerSourceRoot(String jobType, Path root) {
        return ("STRUCTURED".equalsIgnoreCase(jobType) || "ANTI_FRAUD".equalsIgnoreCase(jobType))
                ? root : root.resolve("src").normalize();
    }

    @PostMapping(value = "/structured-case-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "1A. 上传单个结构化案例数据",
            description = """
                    上传后返回 `uploadToken`，供“新建上传任务”使用。单案例必须包含
                    `basic_info.json` 与 `customers.json`；`accounts.json` 和 `other_entities.json` 可选。

                    - NEW：系统生成可疑报告，禁止上传 analysis_texts.json；
                    - HISTORICAL：复用历史可疑报告，必须上传 analysis_texts.json；
                    - 单个文件及全部文件合计均不得超过 100MB。
                    """,
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> uploadStructuredCaseFile(
            @Parameter(description = "basic_info.json；顶层对象，必须包含非空 case_id", required = true)
            @RequestPart("basicInfo") MultipartFile basicInfo,
            @Parameter(description = "customers.json；非空客户数组，每项必须包含 entity_id", required = true)
            @RequestPart("customers") MultipartFile customers,
            @Parameter(description = "识别类型：NEW=新增案例，HISTORICAL=历史案例", example = "NEW",
                    schema = @Schema(allowableValues = {"NEW", "HISTORICAL"}))
            @RequestParam(value = "recognitionMode", required = false) String recognitionMode,
            @Parameter(description = "analysis_texts.json；仅 HISTORICAL 必传")
            @RequestPart(value = "analysisTexts", required = false) MultipartFile analysisTexts,
            @Parameter(description = "accounts.json；可选账户数组")
            @RequestPart(value = "accounts", required = false) MultipartFile accounts,
            @Parameter(description = "other_entities.json；可选其他实体数组")
            @RequestPart(value = "otherEntities", required = false) MultipartFile otherEntities) throws Exception {
        String resolvedRecognitionMode = recognitionMode == null ? "" : recognitionMode.trim().toUpperCase();
        if (!List.of("NEW", "HISTORICAL").contains(resolvedRecognitionMode)) {
            return CommonResult.error(400, "案例来源必须是新增案例或历史案例");
        }
        if ("NEW".equals(resolvedRecognitionMode) && analysisTexts != null) {
            return CommonResult.error(400, "新增案例不接收 analysis_texts.json，可疑报告由后续流程生成");
        }
        if ("HISTORICAL".equals(resolvedRecognitionMode) && analysisTexts == null) {
            return CommonResult.error(400, "历史案例必须上传 analysis_texts.json，框架抽取将复用已有分析文本");
        }
        List<MultipartFile> supplied = java.util.stream.Stream.of(
                        basicInfo, customers, analysisTexts, accounts, otherEntities)
                .filter(java.util.Objects::nonNull).toList();
        if (basicInfo.isEmpty() || customers.isEmpty()) {
            return CommonResult.error(400, "单案例处理必须上传 basic_info.json 和 customers.json");
        }
        if (supplied.stream().anyMatch(MultipartFile::isEmpty)) {
            return CommonResult.error(400, "已选择的 JSON 文件不能为空");
        }
        if (supplied.stream().anyMatch(file -> !isJsonFile(file))) {
            return CommonResult.error(400, "单案例处理仅支持 JSON 文件");
        }
        long totalSize = supplied.stream().mapToLong(MultipartFile::getSize).sum();
        if (supplied.stream().anyMatch(file -> file.getSize() > STRUCTURED_CASE_UPLOAD_LIMIT)
                || totalSize > STRUCTURED_CASE_UPLOAD_LIMIT) {
            return CommonResult.error(400, "单案例文件总大小不能超过100MB");
        }

        String validationError = validateStructuredCaseFiles(
                basicInfo, customers, analysisTexts, accounts, otherEntities);
        if (validationError != null) return CommonResult.error(400, validationError);

        Path uploadRoot = structuredCaseWorkerRoot.resolve("uploads").normalize();
        Files.createDirectories(uploadRoot);
        String token = UUID.randomUUID().toString();
        Path target = uploadRoot.resolve(token).normalize();
        if (!target.startsWith(uploadRoot)) return CommonResult.error(400, "上传路径非法");
        Files.createDirectory(target);
        saveStructuredCaseFile(target, "basic_info.json", basicInfo);
        saveStructuredCaseFile(target, "customers.json", customers);
        saveStructuredCaseFile(target, "analysis_texts.json", analysisTexts);
        saveStructuredCaseFile(target, "accounts.json", accounts);
        saveStructuredCaseFile(target, "other_entities.json", otherEntities);
        List<String> fileNames = new java.util.ArrayList<>(List.of("basic_info.json", "customers.json"));
        if (analysisTexts != null) fileNames.add("analysis_texts.json");
        if (accounts != null) fileNames.add("accounts.json");
        if (otherEntities != null) fileNames.add("other_entities.json");
        return CommonResult.success(Map.of(
                "uploadToken", token,
                "fileName", String.join(" + ", fileNames),
                "fileNames", fileNames,
                "fileSize", totalSize,
                "processingMode", "SINGLE",
                "caseType", resolvedRecognitionMode,
                "recognitionMode", resolvedRecognitionMode));
    }

    CommonResult<Map<String, Object>> uploadStructuredCaseFile(
            MultipartFile basicInfo, MultipartFile customers, MultipartFile analysisTexts,
            MultipartFile accounts, MultipartFile otherEntities) throws Exception {
        return uploadStructuredCaseFile(basicInfo, customers,
                analysisTexts == null ? "NEW" : "HISTORICAL",
                analysisTexts, accounts, otherEntities);
    }

    @PostMapping(value = "/anti-fraud-case-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传单个反欺诈案例",
            description = "基础信息、客户、账户、设备为必填直映射数据；NEW 另传 event_chain.json，HISTORICAL 另传 text_analysis.json。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> uploadAntiFraudCaseFiles(
            @RequestPart("basicInfo") MultipartFile basicInfo,
            @RequestPart("customers") MultipartFile customers,
            @RequestPart("accounts") MultipartFile accounts,
            @RequestPart("devices") MultipartFile devices,
            @RequestParam("recognitionMode") String recognitionMode,
            @RequestPart(value = "eventChain", required = false) MultipartFile eventChain,
            @RequestPart(value = "textAnalysis", required = false) MultipartFile textAnalysis) throws Exception {
        String mode = recognitionMode == null ? "" : recognitionMode.trim().toUpperCase();
        if (!List.of("NEW", "HISTORICAL").contains(mode)) {
            return CommonResult.error(400, "案例来源必须是新增案例或历史案例");
        }
        if ("NEW".equals(mode) && eventChain == null) {
            return CommonResult.error(400, "新增反欺诈案例必须上传 event_chain.json");
        }
        if ("HISTORICAL".equals(mode) && textAnalysis == null) {
            return CommonResult.error(400, "历史反欺诈案例必须上传 text_analysis.json");
        }
        if ("NEW".equals(mode) && textAnalysis != null) {
            return CommonResult.error(400, "新增反欺诈案例不接收 text_analysis.json，可疑报告由后续流程生成");
        }
        List<MultipartFile> supplied = java.util.stream.Stream.of(
                        basicInfo, customers, accounts, devices, eventChain, textAnalysis)
                .filter(java.util.Objects::nonNull).toList();
        if (supplied.stream().anyMatch(MultipartFile::isEmpty)
                || supplied.stream().anyMatch(file -> !isJsonFile(file))) {
            return CommonResult.error(400, "反欺诈案例必须上传非空 JSON 文件");
        }
        long totalSize = supplied.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > STRUCTURED_CASE_UPLOAD_LIMIT
                || supplied.stream().anyMatch(file -> file.getSize() > STRUCTURED_CASE_UPLOAD_LIMIT)) {
            return CommonResult.error(400, "反欺诈单案例文件总大小不能超过100MB");
        }
        String validationError = validateAntiFraudCaseFiles(
                basicInfo, customers, accounts, devices, eventChain, textAnalysis, mode);
        if (validationError != null) return CommonResult.error(400, validationError);

        Path uploadRoot = antiFraudCaseWorkerRoot.resolve("uploads").normalize();
        Files.createDirectories(uploadRoot);
        String token = UUID.randomUUID().toString();
        Path target = uploadRoot.resolve(token).normalize();
        if (!target.startsWith(uploadRoot)) return CommonResult.error(400, "上传路径非法");
        Files.createDirectory(target);
        saveStructuredCaseFile(target, "basic_info.json", basicInfo);
        saveStructuredCaseFile(target, "customers.json", customers);
        saveStructuredCaseFile(target, "accounts.json", accounts);
        saveStructuredCaseFile(target, "devices.json", devices);
        saveStructuredCaseFile(target, "event_chain.json", eventChain);
        saveStructuredCaseFile(target, "text_analysis.json", textAnalysis);
        List<String> fileNames = new java.util.ArrayList<>(List.of(
                "basic_info.json", "customers.json", "accounts.json", "devices.json"));
        if (eventChain != null) fileNames.add("event_chain.json");
        if (textAnalysis != null) fileNames.add("text_analysis.json");
        return CommonResult.success(Map.of(
                "uploadToken", token,
                "fileName", String.join(" + ", fileNames),
                "fileNames", fileNames,
                "fileSize", totalSize,
                "processingMode", "SINGLE",
                "recognitionMode", mode,
                "workflow", "ANTI_FRAUD_CASE_PIPELINE"));
    }

    @PostMapping(value = "/structured-case-batch-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "1B. 上传批量结构化案例数据",
            description = "上传一个 CSV 或 XLSX（最大 100MB），服务端校验后返回 uploadToken、案例数和校验结果。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> uploadStructuredCaseBatchFile(
            @Parameter(description = "批量案例 CSV/XLSX 文件", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "识别类型", required = true, example = "NEW",
                    schema = @Schema(allowableValues = {"NEW", "HISTORICAL"}))
            @RequestParam("recognitionMode") String recognitionMode) throws Exception {
        String mode = recognitionMode == null ? "" : recognitionMode.trim().toUpperCase();
        if (!List.of("NEW", "HISTORICAL").contains(mode)) {
            return CommonResult.error(400, "案例来源必须是新增案例或历史案例");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lowerName = originalName.toLowerCase();
        String suffix = lowerName.endsWith(".xlsx") ? ".xlsx" : lowerName.endsWith(".csv") ? ".csv" : "";
        if (suffix.isBlank() || file.isEmpty()) {
            return CommonResult.error(400, "批处理必须上传非空的 .csv 或 .xlsx 文件");
        }
        if (file.getSize() > STRUCTURED_CASE_UPLOAD_LIMIT) {
            return CommonResult.error(400, "批处理文件不能超过100MB");
        }
        Path uploadRoot = structuredCaseWorkerRoot.resolve("uploads").normalize();
        Files.createDirectories(uploadRoot);
        String token = UUID.randomUUID().toString();
        Path target = uploadRoot.resolve(token).normalize();
        if (!target.startsWith(uploadRoot)) return CommonResult.error(400, "上传路径非法");
        Files.createDirectory(target);
        Path saved = target.resolve("cases" + suffix).normalize();
        try {
            Files.copy(file.getInputStream(), saved, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> validation = workerService.validateStructuredCaseBatch(saved, mode);
            Map<String, Object> result = new java.util.LinkedHashMap<>(validation);
            result.put("uploadToken", token);
            result.put("fileName", originalName);
            result.put("fileSize", file.getSize());
            result.put("processingMode", "BATCH");
            result.put("recognitionMode", mode);
            return CommonResult.success(result);
        } catch (Exception ex) {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(target);
            return CommonResult.error(400, ex.getMessage());
        }
    }

    @PostMapping(value = "/anti-fraud-case-batch-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传批量反欺诈案例数据",
            description = "上传一个 CSV/XLSX（最大 100MB）。NEW 第五列为 event_chain；HISTORICAL 第五列为 text_analysis。每个单元格为对应 JSON。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> uploadAntiFraudCaseBatchFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("recognitionMode") String recognitionMode) throws Exception {
        String mode = recognitionMode == null ? "" : recognitionMode.trim().toUpperCase();
        if (!List.of("NEW", "HISTORICAL").contains(mode)) {
            return CommonResult.error(400, "案例来源必须是新增案例或历史案例");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lowerName = originalName.toLowerCase();
        String suffix = lowerName.endsWith(".xlsx") ? ".xlsx" : lowerName.endsWith(".csv") ? ".csv" : "";
        if (suffix.isBlank() || file.isEmpty()) {
            return CommonResult.error(400, "反欺诈批处理必须上传非空的 .csv 或 .xlsx 文件");
        }
        if (file.getSize() > STRUCTURED_CASE_UPLOAD_LIMIT) {
            return CommonResult.error(400, "反欺诈批处理文件不能超过100MB");
        }
        Path uploadRoot = antiFraudCaseWorkerRoot.resolve("uploads").normalize();
        Files.createDirectories(uploadRoot);
        String token = UUID.randomUUID().toString();
        Path target = uploadRoot.resolve(token).normalize();
        if (!target.startsWith(uploadRoot)) return CommonResult.error(400, "上传路径非法");
        Files.createDirectory(target);
        Path saved = target.resolve("cases" + suffix).normalize();
        try {
            Files.copy(file.getInputStream(), saved, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> validation = workerService.validateAntiFraudCaseBatch(saved, mode);
            Map<String, Object> result = new java.util.LinkedHashMap<>(validation);
            result.put("uploadToken", token);
            result.put("fileName", originalName);
            result.put("fileSize", file.getSize());
            result.put("processingMode", "BATCH");
            result.put("recognitionMode", mode);
            result.put("workflow", "ANTI_FRAUD_CASE_PIPELINE");
            return CommonResult.success(result);
        } catch (Exception ex) {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(target);
            return CommonResult.error(400, ex.getMessage());
        }
    }

    private boolean isJsonFile(MultipartFile file) {
        String original = file.getOriginalFilename();
        return original != null && original.toLowerCase().endsWith(".json");
    }

    private String validateStructuredCaseFiles(
            MultipartFile basicInfo, MultipartFile customers, MultipartFile analysisTexts,
            MultipartFile accounts, MultipartFile otherEntities) {
        try {
            JsonNode basic = JSON.readTree(basicInfo.getInputStream());
            if (basic == null || !basic.isObject()) return "basic_info.json 顶层必须是 JSON 对象";
            if (basic.path("case_id").asText("").isBlank()) return "basic_info.json 必须包含非空 case_id";

            JsonNode customerList = JSON.readTree(customers.getInputStream());
            if (customerList == null || !customerList.isArray() || customerList.isEmpty()) {
                return "customers.json 顶层必须是非空 JSON 数组";
            }
            for (JsonNode customer : customerList) {
                if (!customer.isObject() || customer.path("entity_id").asText("").isBlank()) {
                    return "customers.json 中每个客户都必须是包含非空 entity_id 的对象";
                }
            }

            if (analysisTexts != null) {
                JsonNode analysis = JSON.readTree(analysisTexts.getInputStream());
                if (analysis == null || !analysis.isObject() || analysis.isEmpty()) {
                    return "analysis_texts.json 顶层必须是非空 JSON 对象";
                }
                boolean hasText = false;
                for (JsonNode text : analysis) {
                    if (text.isTextual() && !text.asText().isBlank()) {
                        hasText = true;
                        break;
                    }
                }
                if (!hasText) return "analysis_texts.json 至少需要包含一段非空分析文本";
            }
            String accountsError = validateOptionalArray(accounts, "accounts.json");
            if (accountsError != null) return accountsError;
            return validateOptionalArray(otherEntities, "other_entities.json");
        } catch (Exception ex) {
            return "案例文件不是有效 JSON：" + ex.getMessage();
        }
    }

    private String validateAntiFraudCaseFiles(
            MultipartFile basicInfo, MultipartFile customers, MultipartFile accounts,
            MultipartFile devices, MultipartFile eventChain, MultipartFile textAnalysis,
            String recognitionMode) {
        try {
            JsonNode basic = JSON.readTree(basicInfo.getInputStream());
            if (basic == null || !basic.isObject() || basic.path("case_id").asText("").isBlank()) {
                return "basic_info.json 顶层必须是对象并包含非空 case_id";
            }
            JsonNode customerList = JSON.readTree(customers.getInputStream());
            if (customerList == null || !customerList.isArray() || customerList.isEmpty()) {
                return "customers.json 顶层必须是非空数组";
            }
            for (JsonNode customer : customerList) {
                if (!customer.isObject() || customer.path("entity_id").asText("").isBlank()) {
                    return "customers.json 中每个客户必须包含非空 entity_id";
                }
            }
            JsonNode accountList = JSON.readTree(accounts.getInputStream());
            if (accountList == null || !accountList.isArray()) return "accounts.json 顶层必须是数组";
            for (JsonNode account : accountList) {
                if (!account.isObject() || account.path("entity_id").asText("").isBlank()) {
                    return "accounts.json 中每个账户必须包含非空 entity_id";
                }
            }
            JsonNode deviceList = JSON.readTree(devices.getInputStream());
            if (deviceList == null || !deviceList.isArray()) return "devices.json 顶层必须是数组";
            for (JsonNode device : deviceList) {
                if (!device.isObject() || (device.path("设备号").asText("").isBlank()
                        && device.path("device_id").asText("").isBlank())) {
                    return "devices.json 中每个设备必须包含设备号或 device_id";
                }
            }
            if ("NEW".equals(recognitionMode)) {
                JsonNode chain = JSON.readTree(eventChain.getInputStream());
                if (chain == null || !chain.isArray() || chain.isEmpty()) {
                    return "event_chain.json 顶层必须是非空数组";
                }
            } else {
                JsonNode analysis = JSON.readTree(textAnalysis.getInputStream());
                if (analysis == null || !analysis.isObject()
                        || (analysis.path("text").asText("").isBlank()
                        && analysis.path("analysis_text").asText("").isBlank())) {
                    return "text_analysis.json 必须包含非空 text 或 analysis_text";
                }
            }
            return null;
        } catch (Exception ex) {
            return "反欺诈案例文件不是有效 JSON：" + ex.getMessage();
        }
    }

    private String validateOptionalArray(MultipartFile upload, String name) throws Exception {
        if (upload == null) return null;
        JsonNode value = JSON.readTree(upload.getInputStream());
        return value != null && value.isArray() ? null : name + " 顶层必须是 JSON 数组";
    }

    private void saveStructuredCaseFile(Path directory, String name, MultipartFile upload) throws Exception {
        if (upload == null) return;
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("上传路径非法");
        Files.copy(upload.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @PostMapping("/jobs")
    @Operation(
            summary = "2-4. 创建并执行案例上传任务",
            description = """
                    使用上传接口返回的 uploadToken 创建任务，并自动排队执行。结构化案例标准步骤为：

                    1. TEXT：NEW 生成可疑报告，HISTORICAL 复用 analysis_texts.json；
                    2. FRAMEWORK：从报告抽取 basic_info、customers、accounts、other_entities、events、relationships、evidences；
                    3. GRAPH：生成并持久化图谱快照；
                    4. ANALYSIS：与固定历史案例快照执行 BGE/Reranker/GED 相似度匹配；
                    5. PERSIST：固化案例、任务血缘和处理结果。

                    `jobType` 必须为 STRUCTURED，`inputParams` 是 JSON 字符串，其中 workflow 必须为
                    XI_AN_CASE_PIPELINE，并携带 processingMode、recognitionMode 和 uploadToken。
                    """,
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    @Transactional
    public CommonResult<Map<String, Object>> create(@RequestBody JobRequest request) {
        String sourceBankCode = sourceBatchBankCode(request);
        if (sourceBankCode != null && request.bankCode() != null && !request.bankCode().isBlank()
                && !sourceBankCode.equals(request.bankCode())) {
            return CommonResult.error(400, "任务银行必须与所选数据批次银行一致");
        }
        String bankCode = sourceBankCode == null
                ? currentUser.scopedBankCode(request.bankCode()) : sourceBankCode;
        currentUser.requireAccessToBank(bankCode);
        if (bankCode == null || request.workspaceId() == null || request.jobType() == null
                || request.jobName() == null || request.steps() == null || request.steps().isEmpty()) {
            return CommonResult.error(400, "bankCode, workspaceId, jobType, jobName and steps are required");
        }
        if (request.scenarioCode() == null || request.scenarioCode().isBlank()) {
            return CommonResult.error(400, "必须从已启用的业务场景中选择场景");
        }
        Integer scenarioCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_scenario_template
                WHERE scenario_code=? AND status='ACTIVE'
                  AND (bank_code IS NULL OR bank_code=?)
                """, Integer.class, request.scenarioCode().trim(), bankCode);
        if (scenarioCount == null || scenarioCount == 0) {
            return CommonResult.error(400, "所选业务场景不在已启用的场景元数据中");
        }
        String validationError = validateJobInput(request, bankCode);
        if (validationError != null) return CommonResult.error(400, validationError);
        if ("UNSTRUCTURED".equalsIgnoreCase(request.jobType()) && request.batchId() != null
                && inputParamBoolean(request.inputParams(), "expandBatch")) {
            List<Long> documentIds = jdbcTemplate.queryForList("""
                SELECT id FROM unstructured_document
                WHERE batch_id=? AND validation_status='VALID' AND COALESCE(extracted_text,'')<>''
                ORDER BY id
                """, Long.class, request.batchId());
            if (documentIds.isEmpty()) return CommonResult.error(400, "所选批次没有已通过校验的文档");
            List<String> jobIds = new java.util.ArrayList<>();
            for (Long documentId : documentIds) {
                String params = "{\"documentId\":" + documentId + "}";
                jobIds.add(insertJob(request, bankCode, params,
                        request.jobName() + "-文档" + documentId));
            }
            jobIds.forEach(this::queueJob);
            return CommonResult.success(Map.of("jobIds", jobIds, "jobCount", jobIds.size(),
                    "batchId", request.batchId(), "status", "QUEUED"));
        }
        String jobId = insertJob(request, bankCode,
                request.inputParams() == null ? "{}" : request.inputParams(), request.jobName());
        queueJob(jobId);
        return CommonResult.success(job(jobId));
    }

    private boolean inputParamBoolean(String inputParams, String field) {
        if (inputParams == null || inputParams.isBlank()) return false;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(inputParams).path(field).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String inputParamText(String inputParams, String field) {
        if (inputParams == null || inputParams.isBlank()) return "";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(inputParams).path(field).asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sourceBatchBankCode(JobRequest request) {
        if (request.batchId() == null || request.jobType() == null
                || !"UNSTRUCTURED".equalsIgnoreCase(request.jobType())) return null;
        String table = "unstructured_ingest_batch";
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT bank_code FROM " + table + " WHERE id=?", String.class, request.batchId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String insertJob(JobRequest request, String bankCode, String inputParams, String jobName) {
        String jobId = "JOB-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO analysis_job
                (job_id, bank_code, workspace_id, batch_id, job_type, job_name, scenario_code,
                 input_params, status, progress, total_steps, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), 'PENDING', 0, ?, ?)
                """, jobId, bankCode, request.workspaceId(), request.batchId(), request.jobType(), jobName,
                request.scenarioCode(), inputParams,
                request.steps().size(), currentUser.username());
        for (int i = 0; i < request.steps().size(); i++) {
            StepRequest step = request.steps().get(i);
            jdbcTemplate.update("""
                    INSERT INTO analysis_job_step
                    (job_id, step_order, step_name, step_type, status, progress)
                    VALUES (?, ?, ?, ?, 'PENDING', 0)
                    """, jobId, i + 1, step.stepName(), step.stepType());
        }
        return jobId;
    }

    private String validateJobInput(JobRequest request, String bankCode) {
        String jobType = request.jobType() == null ? "" : request.jobType().trim().toUpperCase();
        if (List.of("IDENTIFICATION", "PATTERN").contains(jobType)) {
            return "旧交易识别/聚类流程已退役；请使用结构化案例识别流程";
        }
        if ("UNSTRUCTURED".equalsIgnoreCase(request.jobType()) && request.batchId() != null) {
            List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                    "SELECT bank_code,workspace_id,status FROM unstructured_ingest_batch WHERE id=?", request.batchId());
            if (rows.isEmpty()) return "所选非结构化数据批次不存在";
            Map<String,Object> batch = rows.get(0);
            if (!bankCode.equals(String.valueOf(batch.get("bank_code")))) return "任务银行与数据批次银行不一致";
            if (((Number) batch.get("workspace_id")).longValue() != request.workspaceId()) return "任务工作空间与数据批次不一致";
            if (!"READY".equals(String.valueOf(batch.get("status")))) return "非结构化数据批次尚未完成上传与校验";
            return null;
        }
        if (!"STRUCTURED".equals(jobType)) return "不支持的案例上传任务类型";
        if ("STRUCTURED".equalsIgnoreCase(request.jobType())) {
            String workflow = inputParamText(request.inputParams(), "workflow");
            boolean antiFraud = "ANTI_FRAUD_CASE_PIPELINE".equals(workflow);
            if (!antiFraud && !"XI_AN_CASE_PIPELINE".equals(workflow)) {
                return "结构化案例识别必须使用 XI_AN_CASE_PIPELINE 或 ANTI_FRAUD_CASE_PIPELINE 工作流";
            }
            if (antiFraud != "ANTI_FRAUD".equalsIgnoreCase(request.scenarioCode())) {
                return "任务业务场景与案例识别工作流不一致";
            }
            String token = inputParamText(request.inputParams(), "uploadToken");
            String mode = inputParamText(request.inputParams(), "processingMode").toUpperCase();
            String recognitionMode = inputParamText(request.inputParams(), "recognitionMode").toUpperCase();
            if (!token.matches("[0-9a-fA-F-]{36}")) return "结构化案例上传凭据无效";
            if (!List.of("SINGLE", "BATCH").contains(mode)) return "处理方式必须是单案例或批处理";
            Path caseWorkerRoot = antiFraud ? antiFraudCaseWorkerRoot : structuredCaseWorkerRoot;
            Path uploadRoot = caseWorkerRoot.resolve("uploads").normalize();
            Path source = uploadRoot.resolve(token).normalize();
            if (!source.startsWith(uploadRoot) || !Files.isDirectory(source)) return "上传案例目录不存在或已失效";
            if (!List.of("NEW", "HISTORICAL").contains(recognitionMode)) {
                return "案例来源必须是新增案例或历史案例";
            }
            if ("BATCH".equals(mode)) {
                try (var files = Files.list(source)) {
                    long batchFiles = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase().matches(".*\\.(csv|xlsx)$"))
                            .count();
                    if (batchFiles != 1) return "批处理上传目录必须且只能包含一个 CSV/XLSX 文件";
                } catch (Exception ex) {
                    return "批处理上传文件不可访问";
                }
            } else {
                List<String> required = antiFraud
                        ? List.of("basic_info.json", "customers.json", "accounts.json", "devices.json")
                        : List.of("basic_info.json", "customers.json");
                if (required.stream().anyMatch(name -> !Files.isRegularFile(source.resolve(name)))) {
                    return "单案例处理缺少必填 JSON 文件";
                }
                if (antiFraud && "NEW".equals(recognitionMode)
                        && !Files.isRegularFile(source.resolve("event_chain.json"))) {
                    return "新增反欺诈案例必须上传 event_chain.json";
                }
                if (antiFraud && "HISTORICAL".equals(recognitionMode)
                        && !Files.isRegularFile(source.resolve("text_analysis.json"))) {
                    return "历史反欺诈案例必须上传 text_analysis.json";
                }
                if (!antiFraud && "HISTORICAL".equals(recognitionMode)
                        && !Files.isRegularFile(source.resolve("analysis_texts.json"))) {
                    return "历史反洗钱案例必须上传 analysis_texts.json";
                }
            }
            return null;
        }
        return "结构化案例识别必须使用 XI_AN_CASE_PIPELINE 或 ANTI_FRAUD_CASE_PIPELINE 工作流";
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(
            summary = "5. 查询上传任务、失败明细与分步结果",
            description = "轮询上传任务的校验、入库及历史案例框架抽取、basic_info.risk_level 自动定级和入图进度；缺少或无法识别风险等级的历史案例转入复核审批；失败时返回当前原因、阶段明细及历次重试记录。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> detail(@PathVariable String jobId) {
        Map<String, Object> job = job(jobId);
        currentUser.requireAccessToBank((String) job.get("bankCode"));
        job.put("steps", jdbcTemplate.queryForList("""
                SELECT step_order AS "stepOrder", step_name AS "stepName", step_type AS "stepType",
                       status, progress, result_json::text AS "result", error_message AS "errorMessage",
                       started_at AS "startedAt", completed_at AS "completedAt"
                FROM analysis_job_step WHERE job_id = ? ORDER BY step_order
                """, jobId));
        job.put("failureHistory", jdbcTemplate.queryForList("""
                SELECT id,failed_step_order AS "failedStepOrder",failed_step_name AS "failedStepName",
                       error_message AS "errorMessage",failure_details::text AS "failureDetails",
                       failed_at AS "failedAt",retried_at AS "retriedAt",retried_by AS "retriedBy"
                  FROM upload_job_failure_attempt WHERE job_id=? ORDER BY failed_at DESC
                """, jobId));
        return CommonResult.success(job);
    }

    @PutMapping("/jobs/{jobId}/structured-results/{caseId}/analysis-text")
    @Transactional
    public CommonResult<Map<String, Object>> updateStructuredResultAnalysisText(
            @PathVariable String jobId,
            @PathVariable String caseId,
            @RequestBody AnalysisTextUpdate request) throws Exception {
        assertJobAccess(jobId);
        String analysisText = request.analysisText() == null ? "" : request.analysisText().trim();
        if (analysisText.isBlank()) return CommonResult.error(400, "案例分析文本不能为空");
        if (analysisText.length() > 100_000) return CommonResult.error(400, "案例分析文本不能超过100000个字符");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.step_order AS "stepOrder", s.result_json::text AS "result"
                FROM analysis_job_step s
                JOIN analysis_job j ON j.job_id=s.job_id
                WHERE s.job_id=? AND j.job_type='STRUCTURED' AND j.deleted=false
                  AND s.status='SUCCEEDED' AND s.result_json ? 'results'
                ORDER BY s.step_order DESC LIMIT 1
                FOR UPDATE OF s
                """, jobId);
        if (rows.isEmpty()) return CommonResult.error(404, "结构化案例结果不存在");

        JsonNode root = JSON.readTree(String.valueOf(rows.get(0).get("result")));
        JsonNode results = root.path("results");
        if (!root.isObject() || !results.isArray()) return CommonResult.error(409, "结构化案例结果格式不支持编辑");
        ObjectNode selected = null;
        for (JsonNode item : results) {
            if (item.isObject() && caseId.equals(item.path("caseId").asText())) {
                selected = (ObjectNode) item;
                break;
            }
        }
        if (selected == null) return CommonResult.error(404, "指定案例不在该任务结果中");

        ObjectNode report = selected.with("analysisReport");
        report.put("analysisText", analysisText);
        ObjectNode analysisTexts = JSON.createObjectNode();
        analysisTexts.put("analysis_text", analysisText);
        report.set("analysisTexts", analysisTexts);
        report.put("source", "USER_EDITED_ANALYSIS_TEXT");
        ObjectNode suspiciousReport = selected.with("suspiciousReport");
        suspiciousReport.put("analysisText", analysisText);
        suspiciousReport.set("analysisTexts", analysisTexts.deepCopy());
        suspiciousReport.put("source", "USER_EDITED_ANALYSIS_TEXT");

        int updated = jdbcTemplate.update("""
                UPDATE analysis_job_step SET result_json=CAST(? AS JSONB)
                WHERE job_id=? AND step_order=?
                """, JSON.writeValueAsString(root), jobId, rows.get(0).get("stepOrder"));
        if (updated == 0) return CommonResult.error(409, "案例分析文本保存失败，请刷新后重试");
        updateStructuredCaseLibraryAnalysisTexts(caseId, analysisTexts);
        return CommonResult.success(Map.of(
                "jobId", jobId,
                "caseId", caseId,
                "analysisText", analysisText,
                "source", "USER_EDITED_ANALYSIS_TEXT"));
    }

    private void updateStructuredCaseLibraryAnalysisTexts(
            String caseId, ObjectNode analysisTexts) throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT case_document::text AS "caseDocument"
                FROM structured_case_library
                WHERE case_id=? AND status='ACTIVE'
                FOR UPDATE
                """, caseId);
        if (rows.isEmpty()) return;
        JsonNode parsed = JSON.readTree(String.valueOf(rows.get(0).get("caseDocument")));
        if (!parsed.isObject()) return;
        ObjectNode caseDocument = (ObjectNode) parsed;
        caseDocument.set("analysis_texts", analysisTexts.deepCopy());
        String caseDocumentJson = JSON.writeValueAsString(caseDocument);
        jdbcTemplate.update("""
                UPDATE structured_case_library
                SET case_document=CAST(? AS JSONB), content_sha256=?, updated_at=CURRENT_TIMESTAMP
                WHERE case_id=? AND status='ACTIVE'
                """, caseDocumentJson, sha256(caseDocumentJson), caseId);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @PutMapping("/jobs/{jobId}/start")
    @Transactional
    public CommonResult<Map<String, Object>> start(@PathVariable String jobId) {
        assertJobAccess(jobId);
        if (!queueJob(jobId)) return CommonResult.error(409, "Only a pending job can be started");
        return CommonResult.success(job(jobId));
    }

    private boolean queueJob(String jobId) {
        int updated = jdbcTemplate.update("""
                UPDATE analysis_job SET current_step = 'QUEUED'
                WHERE job_id = ? AND status = 'PENDING' AND current_step IS NULL
                """, jobId);
        if (updated == 0) return false;
        jdbcTemplate.update("""
                INSERT INTO event_outbox
                (bank_code, aggregate_type, aggregate_id, event_type, business_version, payload_json)
                SELECT bank_code, 'ANALYSIS_JOB', job_id, 'ANALYSIS_JOB_REQUESTED', 1,
                       jsonb_build_object(
                         'jobId', job_id, 'jobType', job_type, 'workspaceId', workspace_id,
                         'scenarioCode', scenario_code, 'totalSteps', total_steps)
                FROM analysis_job WHERE job_id = ?
                ON CONFLICT (aggregate_type, aggregate_id, event_type, business_version) DO NOTHING
                """, jobId);
        workerService.executeAfterCommit(jobId);
        return true;
    }

    @PutMapping("/jobs/{jobId}/steps/{stepOrder}")
    @Transactional
    public CommonResult<Map<String, Object>> updateStep(
            @PathVariable String jobId, @PathVariable int stepOrder, @RequestBody StepUpdate request) {
        assertJobAccess(jobId);
        if (!List.of("RUNNING", "SUCCEEDED", "FAILED").contains(request.status())) {
            return CommonResult.error(400, "Invalid step status");
        }
        int progress = "SUCCEEDED".equals(request.status()) ? 100
                : Math.max(0, Math.min(99, request.progress() == null ? 0 : request.progress()));
        int updated = jdbcTemplate.update("""
                UPDATE analysis_job_step SET status = ?, progress = ?,
                    started_at = CASE WHEN ? = 'RUNNING' THEN COALESCE(started_at, CURRENT_TIMESTAMP) ELSE started_at END,
                    completed_at = CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN CURRENT_TIMESTAMP ELSE NULL END,
                    result_json = CAST(? AS JSONB), error_message = ?
                WHERE job_id = ? AND step_order = ?
                """, request.status(), progress, request.status(), request.status(), request.resultJson(),
                request.errorMessage(), jobId, stepOrder);
        if (updated == 0) return CommonResult.error(404, "Analysis step does not exist");
        refreshJob(jobId);
        return detail(jobId);
    }

    @PutMapping("/jobs/{jobId}/cancel")
    public CommonResult<Map<String, Object>> cancel(@PathVariable String jobId) {
        assertJobAccess(jobId);
        int updated = jdbcTemplate.update("""
                UPDATE analysis_job SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status IN ('PENDING','RUNNING')
                """, jobId);
        return updated == 0 ? CommonResult.error(409, "Job cannot be cancelled")
                : CommonResult.success(job(jobId));
    }

    @PutMapping("/jobs/{jobId}/retry")
    @Transactional
    public CommonResult<Map<String, Object>> retry(@PathVariable String jobId) {
        assertJobAccess(jobId);
        int updated = jdbcTemplate.update("""
                UPDATE analysis_job SET status = 'PENDING', progress = 0, current_step = NULL,
                    started_at = NULL, completed_at = NULL, error_message = NULL
                WHERE job_id = ? AND status = 'FAILED'
                """, jobId);
        if (updated == 0) return CommonResult.error(409, "Only a failed job can be retried");
        jdbcTemplate.update("""
                UPDATE upload_job_failure_attempt SET retried_at=CURRENT_TIMESTAMP,retried_by=?
                 WHERE id=(SELECT id FROM upload_job_failure_attempt
                           WHERE job_id=? AND retried_at IS NULL ORDER BY failed_at DESC LIMIT 1)
                """, currentUser.username(), jobId);
        jdbcTemplate.update("""
                UPDATE analysis_job_step SET status = 'PENDING', progress = 0, started_at = NULL,
                    completed_at = NULL, error_message = NULL
                WHERE job_id = ? AND status = 'FAILED'
                """, jobId);
        queueJob(jobId);
        return CommonResult.success(job(jobId));
    }

    @DeleteMapping("/jobs/{jobId}")
    public CommonResult<Map<String, Object>> delete(@PathVariable String jobId) {
        assertJobAccess(jobId);
        try {
            return CommonResult.success(deletionService.deleteJob(jobId));
        } catch (IllegalStateException ex) {
            return CommonResult.error(409, ex.getMessage());
        }
    }

    private void assertJobAccess(String jobId) {
        currentUser.requireAccessToBank((String) job(jobId).get("bankCode"));
    }

    private Map<String, Object> job(String jobId) {
        return jdbcTemplate.queryForMap("""
                SELECT job_id AS "jobId", bank_code AS "bankCode", workspace_id AS "workspaceId",
                       batch_id AS "batchId", job_type AS "jobType", job_name AS "jobName", scenario_code AS "scenarioCode",
                       input_params->>'processingMode' AS "processingMode",
                       input_params->>'sourceFileName' AS "sourceFileName",
                       CASE WHEN job_type='UNSTRUCTURED'
                         THEN (SELECT batch_no FROM unstructured_ingest_batch WHERE id=analysis_job.batch_id)
                         ELSE (SELECT batch_no FROM risk_ingest_batch WHERE id=analysis_job.batch_id)
                       END AS "batchNo",
                       (SELECT COUNT(*) FROM risk_signal_analysis_job_rel rel
                         WHERE rel.job_id=analysis_job.job_id) AS "signalCount",
                       (SELECT COUNT(*) FROM risk_signal_analysis_job_rel rel
                         JOIN risk_signal s ON s.signal_id=rel.signal_id
                         WHERE rel.job_id=analysis_job.job_id
                           AND s.status NOT IN ('MERGED','REJECTED')) AS "pendingReviewCount",
                       (SELECT COUNT(DISTINCT rel.case_id) FROM case_analysis_job_rel rel
                         WHERE rel.job_id=analysis_job.job_id) AS "caseCount",
                       status, progress, total_steps AS "totalSteps", current_step AS "currentStep",
                       error_message AS "errorMessage", created_at AS "createdAt",
                       started_at AS "startedAt", completed_at AS "completedAt"
                FROM analysis_job WHERE job_id = ? AND deleted=false
                """, jobId);
    }

    private void refreshJob(String jobId) {
        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                       COALESCE(AVG(progress), 0)::INT AS progress,
                       MAX(step_name) FILTER (WHERE status = 'RUNNING') AS current_step
                FROM analysis_job_step WHERE job_id = ?
                """, jobId);
        long failed = ((Number) summary.get("failed")).longValue();
        long total = ((Number) summary.get("total")).longValue();
        long succeeded = ((Number) summary.get("succeeded")).longValue();
        String status = failed > 0 ? "FAILED" : succeeded == total ? "SUCCEEDED" : "RUNNING";
        jdbcTemplate.update("""
                UPDATE analysis_job SET status = ?, progress = ?, current_step = ?,
                    completed_at = CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE job_id = ?
                """, status, summary.get("progress"), summary.get("current_step"), status, jobId);
    }

    @Schema(name = "AnalysisJobRequest", description = "分析任务创建参数；结构化案例流程使用 STRUCTURED 类型")
    public record JobRequest(
            @Schema(description = "银行编码", example = "BANK_XA") String bankCode,
            @Schema(description = "工作空间 ID", example = "1") Long workspaceId,
            @Schema(description = "结构化案例上传模式无需批次 ID", nullable = true) Long batchId,
            @Schema(description = "任务类型", example = "STRUCTURED", allowableValues = {"STRUCTURED"}) String jobType,
            @Schema(description = "任务名称", example = "2026-08-24结构化案例识别") String jobName,
            @Schema(description = "已启用的业务场景编码", example = "AML_STRUCTURED_CASE") String scenarioCode,
            @Schema(description = "JSON 字符串形式的流程输入参数",
                    example = "{\"workflow\":\"XI_AN_CASE_PIPELINE\",\"processingMode\":\"SINGLE\",\"recognitionMode\":\"NEW\",\"uploadToken\":\"8b176f43-5f5f-4d57-87f8-7f53bf201b41\",\"caseCount\":1}")
            String inputParams,
            @Schema(description = "按顺序提交的五个处理步骤") List<StepRequest> steps) {}

    @Schema(name = "AnalysisJobStepRequest")
    public record StepRequest(
            @Schema(description = "步骤名称", example = "可疑报告生成或复用") String stepName,
            @Schema(description = "步骤编码", example = "TEXT",
                    allowableValues = {"TEXT", "FRAMEWORK", "GRAPH", "ANALYSIS", "PERSIST"}) String stepType) {}
    public record StepUpdate(String status, Integer progress, String resultJson, String errorMessage) {}
    public record AnalysisTextUpdate(String analysisText) {}
}
