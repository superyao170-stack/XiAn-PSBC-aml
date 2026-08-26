package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionedContractServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final VersionedContractService service = new VersionedContractService(jdbc, mapper);

    @Test
    void canonicalHashDoesNotDependOnObjectPropertyOrder() throws Exception {
        JsonNode first = mapper.readTree("{\"b\":2,\"a\":1,\"nested\":{\"y\":2,\"x\":1}}");
        JsonNode second = mapper.readTree("{\"nested\":{\"x\":1,\"y\":2},\"a\":1,\"b\":2}");
        assertEquals(service.semanticHash(first), service.semanticHash(second));
    }

    @Test
    void validationAcceptsACompleteCanonicalEvent() throws Exception {
        stubSchema("""
            {"type":"object","required":["contractVersion","eventId","participants"],
             "properties":{"contractVersion":{"const":"CanonicalEvent/1.0"},
             "eventId":{"type":"string"},"participants":{"type":"array","items":{"type":"object"}}}}
            """);
        JsonNode event = mapper.readTree("""
            {"contractVersion":"CanonicalEvent/1.0","eventId":"EV-1","participants":[]}
            """);
        assertTrue(service.validate(VersionedContractService.CANONICAL_EVENT, "1.0", event,
                "BANK001", "TEST", "EV-1").valid());
    }

    @Test
    void validationRejectsMissingRequiredAndWrongConstant() throws Exception {
        stubSchema("""
            {"type":"object","required":["contractVersion","eventId"],
             "properties":{"contractVersion":{"const":"CanonicalEvent/1.0"},"eventId":{"type":"string"}}}
            """);
        JsonNode event = mapper.readTree("{\"contractVersion\":\"CanonicalEvent/2.0\"}");
        VersionedContractService.Validation result = service.validate(
                VersionedContractService.CANONICAL_EVENT, "1.0", event, "BANK001", "TEST", "EV-1");
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("eventId")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("must equal")));
    }

    @Test
    void riskExchangeValidationRejectsPayloadDigestMismatch() throws Exception {
        stubSchema("""
            {"type":"object","required":["payload","payloadDigest"],
             "properties":{"payload":{"type":"object"},"payloadDigest":{"type":"string"}}}
            """);
        JsonNode envelope = mapper.readTree("""
            {"payload":{"riskLevel":"HIGH"},"payloadDigest":"%s"}
            """.formatted("0".repeat(64)));

        VersionedContractService.Validation result = service.validate(
                VersionedContractService.RISK_EXCHANGE, "1.0", envelope,
                "BANK001", "TEST", "MSG-1");

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("payloadDigest")));
    }

    private void stubSchema(String schema) throws Exception {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(schema);
        when(jdbc.queryForObject(anyString(), eq(Object.class), any(Object[].class))).thenReturn(pg);
    }
}
