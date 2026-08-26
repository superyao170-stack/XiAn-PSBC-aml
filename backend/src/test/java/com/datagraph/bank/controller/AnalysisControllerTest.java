package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.AnalysisWorkerService;
import com.datagraph.bank.service.AnalysisJobDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisControllerTest {
    @TempDir
    Path tempDir;
    private JdbcTemplate jdbc;
    private AnalysisWorkerService workerService;
    private AnalysisJobDeletionService deletionService;
    private AnalysisController controller;
    private Path structuredCaseRoot;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        workerService = mock(AnalysisWorkerService.class);
        deletionService = mock(AnalysisJobDeletionService.class);
        structuredCaseRoot = tempDir.resolve("structured-case-worker");
        controller = new AnalysisController(jdbc, currentUser, workerService, deletionService,
                "./worker/unstructured", structuredCaseRoot.toString());

        when(currentUser.scopedBankCode(anyString())).thenReturn("BANK001");
        when(jdbc.queryForObject(contains("risk_scenario_template"),
                eq(Integer.class), eq("AML"), eq("BANK001"))).thenReturn(1);
    }

    @Test
    void legacyTransactionIdentificationJobIsRejected() {
        CommonResult<Map<String, Object>> result = controller.create(new AnalysisController.JobRequest(
                "BANK001", 1L, 1L, "IDENTIFICATION", "旧交易识别测试",
                "AML", "{}", List.of(new AnalysisController.StepRequest("模型评分", "I3"))));

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("已退役"));
        verifyNoInteractions(workerService);
    }

    @Test
    void structuredJobRejectsLegacyBatchClusteringWorkflow() {
        CommonResult<Map<String, Object>> result = controller.create(structuredRequest());

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("XI_AN_CASE_PIPELINE"));
        verifyNoInteractions(workerService);
    }

    @Test
    void singleCaseUploadPersistsRequiredAndOptionalFilesAsOneDirectory() throws Exception {
        MockMultipartFile basic = jsonFile("basicInfo", "basic_info.json", "{\"case_id\":\"CASE-1\"}");
        MockMultipartFile customers = jsonFile("customers", "customers.json", "[{\"entity_id\":\"CUST-1\"}]");
        MockMultipartFile accounts = jsonFile("accounts", "accounts.json", "[]");

        CommonResult<Map<String, Object>> result = controller.uploadStructuredCaseFile(
                basic, customers, "HISTORICAL", null, accounts, null);

        assertEquals(200, result.getCode());
        assertEquals("HISTORICAL", result.getData().get("caseType"));
        Path uploadDirectory = structuredCaseRoot.resolve("uploads")
                .resolve(String.valueOf(result.getData().get("uploadToken")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("basic_info.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("customers.json")));
        assertTrue(Files.notExists(uploadDirectory.resolve("analysis_texts.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("accounts.json")));
        assertTrue(Files.notExists(uploadDirectory.resolve("other_entities.json")));
    }

    @Test
    void singleCaseUploadRejectsCustomerWithoutEntityId() throws Exception {
        MockMultipartFile basic = jsonFile("basicInfo", "basic_info.json", "{\"case_id\":\"CASE-1\"}");
        MockMultipartFile customers = jsonFile("customers", "customers.json", "[{\"customer_name\":\"张三\"}]");

        CommonResult<Map<String, Object>> result = controller.uploadStructuredCaseFile(
                basic, customers, null, null, null);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("entity_id"));
    }

    @Test
    void historicalUploadNoLongerRequiresExistingSuspiciousReport() throws Exception {
        MockMultipartFile basic = jsonFile("basicInfo", "basic_info.json", "{\"case_id\":\"CASE-1\"}");
        MockMultipartFile customers = jsonFile("customers", "customers.json", "[{\"entity_id\":\"CUST-1\"}]");

        CommonResult<Map<String, Object>> result = controller.uploadStructuredCaseFile(
                basic, customers, "HISTORICAL", null, null, null);

        assertEquals(200, result.getCode());
        assertEquals("HISTORICAL", result.getData().get("recognitionMode"));
    }

    @Test
    void newRecognitionRejectsHistoricalReportAndReturnsExplicitMode() throws Exception {
        MockMultipartFile basic = jsonFile("basicInfo", "basic_info.json", "{\"case_id\":\"CASE-1\"}");
        MockMultipartFile customers = jsonFile("customers", "customers.json", "[{\"entity_id\":\"CUST-1\"}]");
        MockMultipartFile analysis = jsonFile("analysisTexts", "analysis_texts.json", "{\"analysis_text1\":\"已有报告\"}");

        CommonResult<Map<String, Object>> rejected = controller.uploadStructuredCaseFile(
                basic, customers, "NEW", analysis, null, null);
        CommonResult<Map<String, Object>> accepted = controller.uploadStructuredCaseFile(
                basic, customers, "NEW", null, null, null);

        assertEquals(400, rejected.getCode());
        assertTrue(rejected.getMessage().contains("后续流程生成"));
        assertEquals(200, accepted.getCode());
        assertEquals("NEW", accepted.getData().get("recognitionMode"));
    }

    @Test
    void antiFraudNewCasePersistsDirectMappingFilesAndEventChain() throws Exception {
        MockMultipartFile basic = jsonFile("basicInfo", "basic_info.json", "{\"case_id\":\"FRD-1\",\"渠道\":\"手机银行\"}");
        MockMultipartFile customers = jsonFile("customers", "customers.json", "[{\"entity_id\":\"CUS-1\"}]");
        MockMultipartFile accounts = jsonFile("accounts", "accounts.json", "[{\"entity_id\":\"ACC-1\"}]");
        MockMultipartFile devices = jsonFile("devices", "devices.json", "[{\"设备号\":\"DEV-1\"}]");
        MockMultipartFile eventChain = jsonFile("eventChain", "event_chain.json", "[{\"发生时间\":\"2026-08-25 10:00:00\",\"类型\":\"设备操作\",\"具体内容\":\"新设备登录\"}]");

        CommonResult<Map<String, Object>> result = controller.uploadAntiFraudCaseFiles(
                basic, customers, accounts, devices, "NEW", eventChain, null);

        assertEquals(200, result.getCode());
        assertEquals("ANTI_FRAUD_CASE_PIPELINE", result.getData().get("workflow"));
        Path antiFraudRoot = structuredCaseRoot.resolveSibling("anti-fraud-case-identification");
        Path uploadDirectory = antiFraudRoot.resolve("uploads")
                .resolve(String.valueOf(result.getData().get("uploadToken")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("basic_info.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("customers.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("accounts.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("devices.json")));
        assertTrue(Files.isRegularFile(uploadDirectory.resolve("event_chain.json")));
        assertTrue(Files.notExists(uploadDirectory.resolve("text_analysis.json")));
    }

    @Test
    void batchUploadValidatesCsvAndReturnsWorkerCaseCount() throws Exception {
        when(workerService.validateStructuredCaseBatch(any(Path.class), eq("HISTORICAL")))
                .thenReturn(Map.of(
                        "status", "SUCCEEDED",
                        "caseCount", 2,
                        "sourceSha256", "abc123"));
        MockMultipartFile batch = new MockMultipartFile(
                "file", "historical_cases.csv", "text/csv",
                ("basic_info,customers,analysis_texts\n"
                        + "\"{}\",\"[]\",\"{}\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        CommonResult<Map<String, Object>> result = controller.uploadStructuredCaseBatchFile(
                batch, "HISTORICAL");

        assertEquals(200, result.getCode());
        assertEquals("BATCH", result.getData().get("processingMode"));
        assertEquals(2, result.getData().get("caseCount"));
        Path upload = structuredCaseRoot.resolve("uploads")
                .resolve(String.valueOf(result.getData().get("uploadToken")))
                .resolve("cases.csv");
        assertTrue(Files.isRegularFile(upload));
        verify(workerService).validateStructuredCaseBatch(upload, "HISTORICAL");
    }

    @Test
    void batchUploadRejectsUnsupportedFileBeforeWorkerValidation() throws Exception {
        MockMultipartFile batch = new MockMultipartFile(
                "file", "cases.json", "application/json", "{}".getBytes());

        CommonResult<Map<String, Object>> result = controller.uploadStructuredCaseBatchFile(
                batch, "NEW");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains(".csv 或 .xlsx"));
        verifyNoInteractions(workerService);
    }

    @Test
    void editingSuspiciousReportDoesNotOverwriteCaseDescriptionAndUpdatesLibrary() throws Exception {
        when(jdbc.queryForMap(contains("FROM analysis_job WHERE"), eq("JOB-1")))
                .thenReturn(Map.of("bankCode", "BANK001"));
        String resultJson = """
                {"results":[{"caseId":"CASE-1",
                  "frameworkExtraction":{"basic_info":{"case_description":"原始案例描述"}},
                  "extractionResult":{"data":{"basic_info":{"case_description":"原始案例描述"}}}}]}
                """;
        when(jdbc.queryForList(contains("s.result_json"), eq("JOB-1")))
                .thenReturn(List.of(Map.of("stepOrder", 5, "result", resultJson)));
        when(jdbc.update(contains("UPDATE analysis_job_step"), anyString(), eq("JOB-1"), eq(5)))
                .thenReturn(1);
        String libraryJson = """
                {"basic_info":{"case_id":"CASE-1","case_description":"原始案例描述"},
                 "analysis_texts":{"analysis_text":"旧报告"}}
                """;
        when(jdbc.queryForList(contains("case_document::text"), eq("CASE-1")))
                .thenReturn(List.of(Map.of("caseDocument", libraryJson)));
        when(jdbc.update(contains("UPDATE structured_case_library"),
                anyString(), anyString(), eq("CASE-1"))).thenReturn(1);

        CommonResult<Map<String, Object>> result = controller.updateStructuredResultAnalysisText(
                "JOB-1", "CASE-1", new AnalysisController.AnalysisTextUpdate("新可疑报告"));

        assertEquals(200, result.getCode());
        var resultJsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE analysis_job_step"),
                resultJsonCaptor.capture(), eq("JOB-1"), eq(5));
        var editedResult = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resultJsonCaptor.getValue()).path("results").get(0);
        assertEquals("原始案例描述",
                editedResult.path("frameworkExtraction").path("basic_info")
                        .path("case_description").asText());
        assertEquals("新可疑报告",
                editedResult.path("suspiciousReport").path("analysisText").asText());

        var libraryJsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE structured_case_library"),
                libraryJsonCaptor.capture(), anyString(), eq("CASE-1"));
        var editedLibrary = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(libraryJsonCaptor.getValue());
        assertEquals("原始案例描述",
                editedLibrary.path("basic_info").path("case_description").asText());
        assertEquals("新可疑报告",
                editedLibrary.path("analysis_texts").path("analysis_text").asText());
    }

    private MockMultipartFile jsonFile(String part, String name, String content) {
        return new MockMultipartFile(part, name, "application/json", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private AnalysisController.JobRequest structuredRequest() {
        return new AnalysisController.JobRequest(
                "BANK001", 1L, null, "STRUCTURED", "结构化案例识别测试",
                "AML", "{}", List.of(new AnalysisController.StepRequest("案例聚类", "CLUSTER")));
    }
}
