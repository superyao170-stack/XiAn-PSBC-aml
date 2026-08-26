package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractProjectionServiceTest {
    @Test
    void legacyExchangeIsAdaptedWithoutPretendingPrivacyWasDeclared() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VersionedContractService contracts = mock(VersionedContractService.class);
        when(contracts.semanticHash(any())).thenReturn("a".repeat(64));
        ContractProjectionService service = new ContractProjectionService(
                mock(JdbcTemplate.class), mapper, contracts);
        ObjectNode result = service.normalizeExchange(mapper.readTree("""
            {"messageId":"M1","messageType":"RISK_CLUE","schemaVersion":"1.0","payload":{"score":0.9}}
            """), "REG001", "BANKGRAPH", "2026-07-23T00:00:00Z");
        assertEquals("RiskExchangeEnvelope/1.0", result.path("contractVersion").asText());
        assertEquals("REG001", result.path("sender").path("institutionCode").asText());
        assertEquals("M1", result.path("idempotencyKey").asText());
        assertTrue(result.path("privacyProcessing").path("containsDirectIdentifiers").asBoolean());
        assertEquals("UNDECLARED_LEGACY_ADAPTER",
                result.path("privacyProcessing").path("identifierMethod").asText());
    }
}
