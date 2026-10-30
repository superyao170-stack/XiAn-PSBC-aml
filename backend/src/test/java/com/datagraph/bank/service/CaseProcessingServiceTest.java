package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseProcessingServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void riskLevelComesOnlyFromFinalCaseBasicInfoRiskLevel() {
        assertEquals("HIGH", CaseProcessingService.riskLevelFromBasicInfo(framework("高")));
        assertEquals("HIGH", CaseProcessingService.riskLevelFromBasicInfo(framework("03-高风险")));
        assertEquals("MEDIUM", CaseProcessingService.riskLevelFromBasicInfo(framework("中")));
        assertEquals("MEDIUM", CaseProcessingService.riskLevelFromBasicInfo(framework("02-重点可疑")));
        assertEquals("LOW", CaseProcessingService.riskLevelFromBasicInfo(framework("低风险")));
        assertEquals("LOW", CaseProcessingService.riskLevelFromBasicInfo(framework("01-一般可疑")));
    }

    @Test
    void missingOrUnknownBasicInfoRiskLevelDoesNotGuess() {
        assertNull(CaseProcessingService.riskLevelFromBasicInfo(framework("")));
        assertNull(CaseProcessingService.riskLevelFromBasicInfo(framework("未知")));
        assertNull(CaseProcessingService.riskLevelFromBasicInfo(json.createObjectNode()));
    }

    @Test
    void similarityStaysPendingWhenNoApprovedHistoricalCases(@TempDir Path workerRoot) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String caseId = "FRD-N-001103";
        when(jdbc.queryForMap(contains("FROM case_processing_pool WHERE case_id=?"), eq(caseId)))
                .thenReturn(Map.of("case_id", caseId, "processing_stage", "PENDING_SIMILARITY",
                        "framework_result", "{}", "bank_code", "bank", "scenario_code", "ANTI_FRAUD"));
        when(jdbc.queryForMap(contains("FROM structured_case_library l JOIN cf_risk_case c"),
                eq("bank"), eq("ANTI_FRAUD"))).thenReturn(Map.of("count", 0L, "updated", ""));
        when(jdbc.queryForList(contains("SELECT l.case_id,l.content_sha256"),
                eq("bank"), eq("ANTI_FRAUD"))).thenReturn(List.of());
        CaseProcessingService service = new CaseProcessingService(jdbc, json,
                mock(CaseRiskRatingService.class), mock(TuGraphStructuredWriter.class),
                workerRoot.toString(), workerRoot.toString(), "python3", "python3", 60, "python3");

        Map<String,Object> response = service.process(List.of(caseId), "SIMILARITY", "tester");

        assertEquals(0L, response.get("succeeded"));
        assertEquals(1L, response.get("failed"));
        Map<?,?> result = (Map<?,?>) ((List<?>) response.get("results")).get(0);
        assertEquals("FAILED", result.get("status"));
        assertTrue(String.valueOf(result.get("error")).contains("暂无已审核通过的案例"));
    }

    @Test
    void amlStagesMaterializeCompleteTransactionFeaturesFromSourcePayload(@TempDir Path workerRoot) throws Exception {
        ObjectNode raw = json.createObjectNode();
        raw.putObject("basic_info").put("case_id", "AML-N-1");
        raw.putArray("customers").addObject().put("entity_id", "CUSTOMER-1");
        ObjectNode features = raw.putObject("transaction_features");
        features.put("schema_version", "1.0").put("case_id", "AML-N-1");
        features.put("stat_window_start", "20251201000000").put("amount_unit", "元");
        var rows = features.putArray("customer_features");
        for (int index = 1; index <= 12; index++) {
            rows.addObject().put("entity_id", "CUSTOMER-" + index)
                    .put("has_transaction_flag", 1).put("tx_cnt_30d", index);
        }

        ReflectionTestUtils.invokeMethod(service(workerRoot), "materializeRaw", workerRoot, raw, false);

        assertEquals(features, json.readTree(Files.readString(workerRoot.resolve("transaction_features.json"))));
        assertFalse(Files.exists(workerRoot.resolve("feature_analysis.json")));
    }

    @Test
    void amlStagesKeepLegacyFeatureArraysAndHistoricalMissingFeaturesCompatible(@TempDir Path workerRoot) throws Exception {
        CaseProcessingService service = service(workerRoot);
        ObjectNode raw = json.createObjectNode();
        raw.putObject("basic_info").put("case_id", "AML-H-1");
        raw.putArray("customers").addObject().put("entity_id", "CUSTOMER-1");
        ReflectionTestUtils.invokeMethod(service, "materializeRaw", workerRoot, raw, false);
        assertFalse(Files.exists(workerRoot.resolve("transaction_features.json")));
        assertFalse(Files.exists(workerRoot.resolve("feature_analysis.json")));

        var legacy = raw.putArray("transaction_features");
        legacy.addObject().put("transaction_id", "TX-1");
        ReflectionTestUtils.invokeMethod(service, "materializeRaw", workerRoot, raw, false);
        assertEquals(legacy, json.readTree(Files.readString(workerRoot.resolve("feature_analysis.json"))));
        assertFalse(Files.exists(workerRoot.resolve("transaction_features.json")));
    }

    private CaseProcessingService service(Path workerRoot) {
        return new CaseProcessingService(mock(JdbcTemplate.class), json,
                mock(CaseRiskRatingService.class), mock(TuGraphStructuredWriter.class),
                workerRoot.toString(), workerRoot.toString(), "python3", "python3", 60, "python3");
    }

    private ObjectNode framework(String riskLevel) {
        ObjectNode framework = json.createObjectNode();
        framework.putObject("basic_info").put("risk_level", riskLevel);
        return framework;
    }
}
