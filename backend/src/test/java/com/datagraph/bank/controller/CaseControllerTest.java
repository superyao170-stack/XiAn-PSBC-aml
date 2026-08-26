package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.entity.CfRiskCase;
import com.datagraph.bank.mapper.CfRiskCaseMapper;
import com.datagraph.bank.mapper.CfRiskEventMapper;
import com.datagraph.bank.mapper.RiskSignalMapper;
import com.datagraph.bank.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CaseControllerTest {
    private CfRiskCaseMapper caseMapper;
    private JdbcTemplate jdbcTemplate;
    private CaseController controller;

    @BeforeEach
    void setUp() {
        caseMapper = mock(CfRiskCaseMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new CaseController(
                caseMapper,
                mock(CfRiskEventMapper.class),
                mock(RiskSignalMapper.class),
                jdbcTemplate,
                mock(CurrentUser.class));
    }

    @Test
    void directCaseCreationIsRejectedBecauseCasesMustComeFromRecognition() {
        CfRiskCase input = new CfRiskCase();
        input.setBankCode("BANK001");
        input.setScenarioCode("AML");

        CommonResult<CfRiskCase> result = controller.createCase(input);

        assertEquals(405, result.getCode());
        verify(caseMapper, never()).insert(any());
    }

    @Test
    void submitRejectsNonDraftCase() {
        CfRiskCase existing = caseWithStatus("APPROVED");
        when(caseMapper.selectOne(any())).thenReturn(existing);

        CommonResult<CfRiskCase> result = controller.submitCase(existing.getCaseId());

        assertEquals(409, result.getCode());
        verify(caseMapper, never()).updateById(any());
    }

    @Test
    void deleteAllowsDraftCaseWithDerivedFactsAndSoftDeletesOnlyTheCase() {
        CfRiskCase existing = caseWithStatus("DRAFT");
        when(caseMapper.selectOne(any())).thenReturn(existing);
        when(jdbcTemplate.update(contains("UPDATE cf_risk_case"), any(Object[].class))).thenReturn(1);

        CommonResult<Void> result = controller.deleteCase(existing.getCaseId());

        assertEquals(200, result.getCode());
        verify(jdbcTemplate).update(contains("UPDATE cf_risk_case"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM"), any(Object[].class));
    }

    @Test
    void deleteRejectsCaseAlreadyInReview() {
        CfRiskCase existing = caseWithStatus("IN_REVIEW");
        when(caseMapper.selectOne(any())).thenReturn(existing);

        CommonResult<Void> result = controller.deleteCase(existing.getCaseId());

        assertEquals(409, result.getCode());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reviewPersistsRecordAndMovesToApproval() {
        CfRiskCase existing = caseWithStatus("IN_REVIEW");
        when(caseMapper.selectOne(any())).thenReturn(existing);
        CaseController.ReviewRequest request = new CaseController.ReviewRequest();
        request.setReviewer("reviewer");
        request.setReviewResult("PASSED");
        request.setReviewOpinion("ok");

        CommonResult<CfRiskCase> result = controller.reviewCase(existing.getCaseId(), request);

        assertEquals(200, result.getCode());
        assertEquals("PENDING_APPROVAL", result.getData().getCaseStatus());
        verify(caseMapper).updateById(existing);
        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void overviewUpdateKeepsInternalUidAndPersistsBusinessFields() {
        CfRiskCase existing = caseWithStatus("APPROVED");
        existing.setCaseName("不可在概览修改的名称");
        when(caseMapper.selectOne(any())).thenReturn(existing);
        when(jdbcTemplate.queryForObject(contains("case_framework_option_metadata"),
                eq(Integer.class), any(), any())).thenReturn(1);
        CaseController.CaseOverviewUpdateRequest request =
                new CaseController.CaseOverviewUpdateRequest(
                        "案例描述", "反洗钱", "可疑报告",
                        "01-中国反洗钱监测分析中心", "01-模型筛选",
                        "02-特别紧急", LocalDateTime.of(2026, 7, 27, 0, 0),
                        "重点核查", "重点可疑", "涉嫌集资诈骗",
                        "1001-疑似非法集资", "开展尽职调查");

        CommonResult<CfRiskCase> result =
                controller.updateCaseOverview(existing.getCaseId(), request);

        assertEquals(200, result.getCode());
        assertEquals(1L, result.getData().getId());
        assertEquals("CASE-1", result.getData().getCaseId());
        assertEquals("不可在概览修改的名称", result.getData().getCaseName());
        assertEquals("重点核查", result.getData().getBusinessCaseStatus());
        assertEquals("重点可疑", result.getData().getBusinessRiskLevel());
        verify(caseMapper).updateById(existing);
    }

    @Test
    void overviewUpdateRejectsValueOutsideSpreadsheetOptions() {
        CfRiskCase existing = caseWithStatus("DRAFT");
        when(caseMapper.selectOne(any())).thenReturn(existing);
        CaseController.CaseOverviewUpdateRequest request =
                new CaseController.CaseOverviewUpdateRequest(
                        null, "反洗钱", null, "错误报送方向", null,
                        null, null, null, null, null, null, null);

        CommonResult<CfRiskCase> result =
                controller.updateCaseOverview(existing.getCaseId(), request);

        assertEquals(400, result.getCode());
        verify(caseMapper, never()).updateById(any());
    }

    @Test
    void structuredWorkerResultPrefersCurrentCasePipelineSlice() {
        String slice = """
                {"caseId":"CASE-1","caseIds":["CASE-1"],"workerResult":{
                  "recognitionMode":"HISTORICAL","results":[{
                    "caseId":"CASE-1","suspiciousReport":{"analysisText":"完整可疑报告"}
                  }]}}
                """;
        when(jdbcTemplate.queryForList(
                contains("jsonb_array_elements"),
                eq(String.class),
                eq("JOB-1"), eq("CASE-1"), eq("CASE-1"), eq("CASE-1")))
                .thenReturn(List.of(slice));

        String result = controller.loadCaseWorkerResultJson(
                "JOB-1", "CASE-1", "STRUCTURED");

        assertEquals(slice, result);
        verify(jdbcTemplate, times(1)).queryForList(
                anyString(), eq(String.class),
                any(), any(), any(), any());
    }

    @Test
    void nonStructuredWorkerResultStillReadsTheCompleteLatestStep() {
        when(jdbcTemplate.queryForObject(
                contains("SELECT result_json::text"),
                eq(String.class), eq("JOB-2")))
                .thenReturn("{\"workerResult\":{}}");

        String result = controller.loadCaseWorkerResultJson(
                "JOB-2", "CASE-2", "UNSTRUCTURED");

        assertEquals("{\"workerResult\":{}}", result);
        verify(jdbcTemplate, never()).queryForList(
                anyString(), eq(String.class),
                any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredReportIsRebuiltFromPersistedAnalysisTexts() {
        when(jdbcTemplate.queryForList(
                contains("case_document->'analysis_texts'"), eq("CASE-1")))
                .thenReturn(List.of(Map.of(
                        "recognitionMode", "HISTORICAL",
                        "analysisTexts", "{\"analysis_text1\":\"第一部分\",\"analysis_text2\":\"第二部分\"}")));

        Map<String,Object> result = controller.attachStructuredReport(
                "CASE-1", Map.of("status", "SUCCEEDED"));

        assertEquals("HISTORICAL", result.get("recognitionMode"));
        List<Map<String,Object>> results = (List<Map<String,Object>>) result.get("results");
        Map<String,Object> report = (Map<String,Object>) results.get(0).get("suspiciousReport");
        assertEquals("第一部分\n\n第二部分", report.get("analysisText"));
        assertEquals(false, report.get("generated"));
        assertEquals("EXISTING_ANALYSIS_TEXT", report.get("source"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredDetailUsesCanonicalPostgresFinalCase() {
        when(jdbcTemplate.queryForList(
                contains("FROM structured_case_library l"), eq("CASE-1")))
                .thenReturn(List.of(Map.of(
                        "recognitionMode", "HISTORICAL",
                        "caseDocument", """
                            {"basic_info":{"case_id":"CASE-1"},
                             "customers":[{"entity_id":"CUST-1"},{"entity_id":"CUST-2"}],
                             "accounts":[{"entity_id":"ACC-1"}],
                             "other_entities":[{"entity_id":"ORG-1"}],
                             "events":[{"event_id":"0000001"}],
                             "relationships":[{"relationship_id":"0000001"}],
                             "analysis_texts":{"analysis_text1":"历史报告"}}
                            """,
                        "suspiciousReport", "{}",
                        "graphSnapshot", "{\"nodeCount\":6}",
                        "jobId", "JOB-1")));

        Map<String,Object> response = controller.loadCanonicalStructuredWorkerResult("CASE-1");

        assertEquals("POSTGRESQL_FINAL_CASE", response.get("source"));
        Map<String,Object> worker = (Map<String,Object>) response.get("workerResult");
        Map<String,Object> result = (Map<String,Object>) ((List<?>) worker.get("results")).get(0);
        Map<String,Object> extraction = (Map<String,Object>) result.get("extractionResult");
        Map<String,Object> framework = (Map<String,Object>) extraction.get("data");
        assertEquals(2, ((List<?>) framework.get("customers")).size());
        assertEquals(1, ((List<?>) framework.get("accounts")).size());
        assertEquals(1, ((List<?>) framework.get("other_entities")).size());
        assertEquals(1, ((List<?>) framework.get("relationships")).size());
    }

    private CfRiskCase caseWithStatus(String status) {
        CfRiskCase riskCase = new CfRiskCase();
        riskCase.setId(1L);
        riskCase.setCaseId("CASE-1");
        riskCase.setCaseVersion(1L);
        riskCase.setBankCode("BANK001");
        riskCase.setCaseStatus(status);
        riskCase.setDeleted(false);
        return riskCase;
    }
}
