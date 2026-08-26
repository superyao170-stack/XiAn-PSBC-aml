package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private ObjectNode framework(String riskLevel) {
        ObjectNode framework = json.createObjectNode();
        framework.putObject("basic_info").put("risk_level", riskLevel);
        return framework;
    }
}
