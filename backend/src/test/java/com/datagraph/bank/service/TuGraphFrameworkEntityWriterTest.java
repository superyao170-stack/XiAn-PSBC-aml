package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TuGraphFrameworkEntityWriterTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void writesAccountAndOtherEntitySourcePropertiesAndCaseEdgesWithoutEvents() throws Exception {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        when(session.run(anyString())).thenReturn(result);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        var framework = new ObjectMapper().readTree("""
                {"accounts":[{"entity_id":"ACC-1","account_number":"62220001","holder_name":"张某"}],
                 "other_entities":[{"entity_id":"ENT-1","entity_type":"虚拟资产平台",
                                      "entity_attr_1":"测试平台","entity_attr_2":"境外交易入口"}],
                 "events":[],"relationships":[]}
                """);

            var writer = new TuGraphStructuredWriter(mock(JdbcTemplate.class),
                    mock(ContractProjectionService.class), mock(EventSemanticEnrichmentService.class),
                    "bolt://localhost:7687", "admin", "test");
            ((Driver) ReflectionTestUtils.getField(writer, "structuredDriver")).close();
            ReflectionTestUtils.setField(writer, "structuredDriver", driver);

            var outcome = writer.writeFrameworkCase("CASE-1", "BANK-1", 1, framework);

            assertEquals(3, outcome.get("nodes"));
            assertEquals(2, outcome.get("edges"));
            ArgumentCaptor<Map> accountCaptor = ArgumentCaptor.forClass(Map.class);
            verify(session).run(eq("CALL db.upsertVertex('Account',$rows)"), accountCaptor.capture());
            Map account = (Map) ((List<?>) accountCaptor.getValue().get("rows")).get(0);
            assertEquals("ACCOUNT", account.get("nodeType"));
            assertEquals("CASE-1::ACC-1", account.get("graphId"));
            assertEquals("张某", new ObjectMapper().readTree(account.get("propertiesJson").toString())
                    .path("holder_name").asText());
            ArgumentCaptor<Map> entityCaptor = ArgumentCaptor.forClass(Map.class);
            verify(session).run(eq("CALL db.upsertVertex('OtherEntity',$rows)"), entityCaptor.capture());
            Map entity = (Map) ((List<?>) entityCaptor.getValue().get("rows")).get(0);
            assertEquals("OTHER_ENTITY", entity.get("nodeType"));
            assertEquals("境外交易入口", new ObjectMapper().readTree(entity.get("propertiesJson").toString())
                    .path("entity_attr_2").asText());
            verify(session).run(contains("{type:'Case',key:'sourceGraphId'},{type:'Account'"), anyMap());
            verify(session).run(contains("{type:'Case',key:'sourceGraphId'},{type:'OtherEntity'"), anyMap());
    }
}
