package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisWorkerServiceTest {
    private JdbcTemplate jdbc;
    private AnalysisWorkerService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new AnalysisWorkerService(jdbc, new ObjectMapper(), new SyncTaskExecutor(),
                mock(TuGraphStructuredWriter.class), "./worker/unstructured", "python");
    }

    @Test
    void structuredCaseFrameworkUsesQualityServicesByDefault() {
        Map<String, Object> settings = service.structuredCaseFrameworkSettings();

        assertEquals(true, settings.get("llmEnabled"));
        assertEquals(true, settings.get("corenlpEnabled"));
        assertEquals("http://127.0.0.1:9002", settings.get("corenlpUrl"));
        assertEquals(3, settings.get("serviceMaxAttempts"));
    }

    @Test
    void completedHistoricalCaseIsPersistedForApprovalWithGraphSnapshotAndJobLineage() throws Exception {
        when(jdbc.queryForList(contains("SELECT bank_code FROM cf_risk_case"),
                eq(String.class), eq("CASE-XI-1"))).thenReturn(List.of());
        when(jdbc.update(contains("INSERT INTO cf_risk_case"), any(Object[].class))).thenReturn(1);
        var result = new ObjectMapper().readTree("""
                {
                  "caseId":"CASE-XI-1",
                  "caseName":"医疗领域可疑案例06——药品入院后虚假会议及讲课费闭环",
                  "recognitionMode":"HISTORICAL",
                  "suspiciousReport":{"analysisText":"可疑报告","analysisTexts":{
                    "analysis_text1":"报告第一部分","analysis_text2":"报告第二部分"
                  }},
                  "extractionResult":{"data":{
                    "basic_info":{"case_id":"CASE-XI-1","urgency":"01","risk_level":"高","report_date":"2026-08-23",
                      "business_domain":"反洗钱与医疗腐败风险识别"},
                    "customers":[{"entity_id":"C-1"}],"events":[]
                  }},
                  "graphSnapshot":{"nodes":[],"edges":[],"nodeCount":0,"edgeCount":0}
                }
                """);

        boolean inserted = service.persistXiAnStructuredCase(Map.of(
                "job_id", "JOB-XI-1", "job_type", "STRUCTURED", "bank_code", "B1",
                "workspace_id", 1L, "batch_id", 9L, "scenario_code", "AML"), result);

        assertEquals(true, inserted);
        var caseArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO cf_risk_case")
                        && sql.contains("case_status=EXCLUDED.case_status")
                        && sql.contains("'STRUCTURED_CASE'")),
                caseArguments.capture());
        assertEquals("药品入院后虚假会议及讲课费闭环", caseArguments.getValue()[1]);
        assertEquals("", caseArguments.getValue()[3]);
        assertEquals("APPROVED", caseArguments.getValue()[7]);
        assertEquals("HIGH", caseArguments.getValue()[9]);
        assertEquals("analysis-worker", caseArguments.getValue()[14]);
        assertEquals("01-反洗钱", caseArguments.getValue()[15]);
        verify(jdbc).update(contains("INSERT INTO graph_snapshot"), any(Object[].class));
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE cf_risk_event")
                        && sql.contains("XI_AN_CASE_FRAMEWORK_EXTRACTION")),
                eq("CASE-XI-1"));
        verify(jdbc).update(contains("INSERT INTO structured_case_library"),
                eq("CASE-XI-1"), eq("B1"), eq("AML"), eq("HISTORICAL"),
                argThat((String document) -> document.contains("\"case_id\":\"CASE-XI-1\"")
                        && document.contains("\"analysis_texts\"")
                        && document.contains("报告第一部分")
                        && document.contains("报告第二部分")),
                matches("[0-9a-f]{64}"));
        verify(jdbc).update(contains("INSERT INTO case_processing_pool"),
                eq("CASE-XI-1"), eq("JOB-XI-1"), eq("B1"), eq("AML"),
                eq("HISTORICAL"), eq("APPROVED"), anyString(), anyString(), anyString(),
                anyString(), eq("HIGH"), eq("APPROVED"));
        verify(jdbc).update(contains("INSERT INTO case_analysis_job_rel"),
                eq("CASE-XI-1"), eq("JOB-XI-1"), eq(9L), eq("B1"), eq("STRUCTURED"));
    }

    @Test
    void historicalCaseWithUnknownRiskLevelQueuesForManualApproval() throws Exception {
        when(jdbc.queryForList(contains("SELECT bank_code FROM cf_risk_case"),
                eq(String.class), eq("CASE-XI-UNKNOWN-RISK"))).thenReturn(List.of());
        var result = new ObjectMapper().readTree("""
                {
                  "caseId":"CASE-XI-UNKNOWN-RISK",
                  "recognitionMode":"HISTORICAL",
                  "extractionResult":{"data":{"basic_info":{"case_id":"CASE-XI-UNKNOWN-RISK","risk_level":"未知"},"events":[]}},
                  "graphSnapshot":{"nodes":[],"edges":[],"nodeCount":0,"edgeCount":0}
                }
                """);

        assertTrue(service.persistXiAnStructuredCase(Map.of(
                "job_id", "JOB-XI-2", "job_type", "STRUCTURED", "bank_code", "B1",
                "workspace_id", 1L, "batch_id", 11L, "scenario_code", "AML"), result));

        verify(jdbc).update(contains("INSERT INTO case_processing_pool"),
                eq("CASE-XI-UNKNOWN-RISK"), eq("JOB-XI-2"), eq("B1"), eq("AML"),
                eq("HISTORICAL"), eq("PENDING_APPROVAL"), anyString(), anyString(), anyString(),
                anyString(), isNull(), eq("PENDING_APPROVAL"));
    }

    @Test
    void historicalCaseWithoutRiskLevelQueuesForManualApproval() throws Exception {
        when(jdbc.queryForList(contains("SELECT bank_code FROM cf_risk_case"),
                eq(String.class), eq("CASE-XI-MANUAL"))).thenReturn(List.of());
        var result = new ObjectMapper().readTree("""
                {
                  "caseId":"CASE-XI-MANUAL",
                  "recognitionMode":"HISTORICAL",
                  "extractionResult":{"data":{"basic_info":{"case_id":"CASE-XI-MANUAL"},"events":[]}},
                  "graphSnapshot":{"nodes":[],"edges":[],"nodeCount":0,"edgeCount":0}
                }
                """);

        assertTrue(service.persistXiAnStructuredCase(Map.of(
                "job_id", "JOB-XI-3", "job_type", "STRUCTURED", "bank_code", "B1",
                "workspace_id", 1L, "batch_id", 10L, "scenario_code", "AML"), result));

        verify(jdbc).update(contains("INSERT INTO case_processing_pool"),
                eq("CASE-XI-MANUAL"), eq("JOB-XI-3"), eq("B1"), eq("AML"),
                eq("HISTORICAL"), eq("PENDING_APPROVAL"), anyString(), anyString(), anyString(),
                anyString(), isNull(), eq("PENDING_APPROVAL"));
    }

}
