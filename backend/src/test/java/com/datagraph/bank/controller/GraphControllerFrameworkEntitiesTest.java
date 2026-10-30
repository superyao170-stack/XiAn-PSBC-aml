package com.datagraph.bank.controller;

import com.datagraph.bank.entity.GraphEdge;
import com.datagraph.bank.entity.GraphNode;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.TuGraphStructuredWriter;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphControllerFrameworkEntitiesTest {
    @Test
    @SuppressWarnings("unchecked")
    void returnsOtherEntitiesAndStandaloneAccountsWithDetailsAndDistinctRelationships() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM cf_risk_case"), eq("CASE-1")))
                .thenReturn(List.of(Map.of("case_id", "CASE-1", "bank_code", "BANK-1")));
        var caseNode = node("1", "Case", Map.of("graphId", "CASE-1", "caseId", "CASE-1", "nodeType", "CASE"));
        var account = node("2", "Account", Map.of("graphId", "CASE-1::ACC-1", "caseId", "CASE-1", "nodeType", "ACCOUNT",
                "propertiesJson", "{\"holder_name\":\"张某\",\"account_number\":\"62220001\",\"caseId\":\"WRONG\"}"));
        var entity = node("3", "OtherEntity", Map.of("graphId", "CASE-1::ENT-1", "caseId", "CASE-1", "nodeType", "OTHER_ENTITY",
                "propertiesJson", "{\"entity_type\":\"平台\",\"entity_attr_1\":\"测试平台\"}"));
        var knowledge = node("4", "RiskEventTypeDefinition", Map.of(
                "graphId", "DEF-1", "caseId", "CASE-1", "nodeType", "RISK_EVENT_TYPE_DEFINITION"));
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.startsWith("MATCH (n:Case)")) return rows(record(Map.of("n", nodeValue(caseNode))));
            if (query.startsWith("MATCH (n) WHERE n.caseId")) return rows(
                    record(Map.of("n", nodeValue(account))), record(Map.of("n", nodeValue(entity))),
                    record(Map.of("n", nodeValue(knowledge))));
            if (query.contains("RETURN c,r,n")) return rows(
                    record(Map.of("c", nodeValue(caseNode), "n", nodeValue(entity), "r",
                            relationshipValue("0", "1", "3", Map.of("graphId", "CASE-1::REL-1")))),
                    record(Map.of("c", nodeValue(caseNode), "n", nodeValue(entity), "r",
                            relationshipValue("0", "1", "3", Map.of("graphId", "CASE-1::REL-2")))),
                    record(Map.of("c", nodeValue(caseNode), "n", nodeValue(account), "r",
                            relationshipValue("0", "1", "2", Map.of()))),
                    record(Map.of("c", nodeValue(caseNode), "n", nodeValue(entity), "r",
                            relationshipValue("0", "1", "3", Map.of()))));
            return rows();
        });

            var controller = new GraphController("tugraph", "bolt://localhost:7688", "neo4j", "test",
                    "localhost", 7687, "admin", "test", "localhost", 8182,
                    "BankGraph", jdbc, mock(CurrentUser.class), mock(TuGraphStructuredWriter.class));
            ((Driver) ReflectionTestUtils.getField(controller, "neo4jDriver")).close();
            ReflectionTestUtils.setField(controller, "neo4jDriver", driver);

            var response = controller.getCaseSubgraph("CASE-1");

            assertEquals(200, response.getCode());
            var nodes = (List<GraphNode>) response.getData().get("nodes");
            assertEquals(3, nodes.size(), "框架实体应保留，知识定义仍应排除");
            var accountProperties = nodes.stream().filter(n -> "2".equals(n.getId())).findFirst().orElseThrow().getProperties();
            assertEquals("张某", accountProperties.get("holder_name"));
            assertEquals("62220001", accountProperties.get("account_number"));
            assertEquals("CASE-1", accountProperties.get("caseId"));
            var entityProperties = nodes.stream().filter(n -> "3".equals(n.getId())).findFirst().orElseThrow().getProperties();
            assertEquals("OTHER_ENTITY", entityProperties.get("canonicalType"));
            assertEquals("平台", entityProperties.get("entity_type"));
            var edges = (List<GraphEdge>) response.getData().get("edges");
            assertEquals(4, edges.size(), "端点不同但原生ID相同、端点相同但业务ID不同的关系均须保留");
            assertEquals(4, edges.stream().map(GraphEdge::getId).distinct().count());
            assertTrue(edges.stream().anyMatch(edge -> "CASE-1::REL-1".equals(edge.getId())));
            assertTrue(edges.stream().anyMatch(edge -> "CASE-1::REL-2".equals(edge.getId())));
            assertTrue(edges.stream().anyMatch(edge -> "1:RELATES_TO:2:0".equals(edge.getId())));
            assertTrue(edges.stream().anyMatch(edge -> "1:RELATES_TO:3:0".equals(edge.getId())));
    }

    private Node node(String id, String label, Map<String,Object> properties) {
        Node node = mock(Node.class);
        when(node.elementId()).thenReturn(id);
        when(node.labels()).thenReturn(List.of(label));
        when(node.keys()).thenReturn(properties.keySet());
        when(node.get(anyString())).thenAnswer(i -> Values.value(properties.get(i.getArgument(0))));
        return node;
    }

    private Value nodeValue(Node node) {
        Value value = mock(Value.class);
        when(value.asNode()).thenReturn(node);
        return value;
    }

    private Value relationshipValue(String id, String source, String target, Map<String,Object> properties) {
        Relationship relationship = mock(Relationship.class);
        when(relationship.elementId()).thenReturn(id);
        when(relationship.type()).thenReturn("RELATES_TO");
        when(relationship.startNodeElementId()).thenReturn(source);
        when(relationship.endNodeElementId()).thenReturn(target);
        when(relationship.keys()).thenReturn(properties.keySet());
        when(relationship.get(anyString())).thenAnswer(i -> Values.value(properties.get(i.getArgument(0))));
        Value value = mock(Value.class);
        when(value.asRelationship()).thenReturn(relationship);
        return value;
    }

    private Record record(Map<String,Value> fields) {
        Record record = mock(Record.class);
        when(record.get(anyString())).thenAnswer(i -> fields.get(i.getArgument(0)));
        return record;
    }

    private Result rows(Record... records) {
        Result result = mock(Result.class);
        var iterator = Arrays.asList(records).iterator();
        when(result.hasNext()).thenAnswer(i -> iterator.hasNext());
        when(result.next()).thenAnswer(i -> iterator.next());
        return result;
    }
}
