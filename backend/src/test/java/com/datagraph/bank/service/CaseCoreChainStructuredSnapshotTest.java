package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CaseCoreChainStructuredSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CaseCoreChainService service =
            new CaseCoreChainService(mock(JdbcTemplate.class), json);

    @Test
    void structuredSnapshotAddsCustomerAndReversesCustomerEventRelationship() throws Exception {
        var snapshot = json.readTree("""
                {
                  "nodes":[
                    {"id":"CASE-1","type":"CASE","label":"测试案例","properties":{}},
                    {"id":"CUS-1","type":"CUSTOMER","label":"张某",
                     "properties":{"entity_id":"CUS-1","customer_name":"张某"}},
                    {"id":"EVT-1","type":"EVENT","label":"异常转账","properties":{}}
                  ],
                  "edges":[
                    {"id":"REL-1","source":"CASE-1","target":"EVT-1","type":"包含关系"},
                    {"id":"REL-2","source":"CUS-1","target":"EVT-1","type":"参与关系"}
                  ]
                }
                """);
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("CASE-1", "CASE", "测试案例"));
        nodes.add(node("EVT-1", "EVENT", "异常转账"));
        List<Map<String, Object>> events = List.of(Map.of(
                "event_id", "EVT-1", "event_name", "异常转账"));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("CASE-1", "EVT-1", "INVESTIGATION_SCOPE", "CASE", "EVENT"));

        service.appendStructuredSnapshotFacts("CASE-1", snapshot, events,
                nodes, new LinkedHashSet<>(List.of("CASE-1", "EVT-1")), edges);

        assertTrue(nodes.stream().anyMatch(node -> "CUS-1".equals(node.get("id"))
                && "CUSTOMER".equals(((Map<?, ?>) node.get("properties")).get("canonicalType"))));
        assertTrue(edges.stream().anyMatch(edge -> "EVT-1".equals(edge.get("source"))
                && "CUS-1".equals(edge.get("target")) && "ACTOR".equals(edge.get("type"))));
        assertEquals(2, edges.size(), "案例—事件范围边应复用，不能重复创建");
    }

    private Map<String, Object> node(String id, String type, String name) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("canonicalType", type);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("uid", id);
        node.put("label", type);
        node.put("name", name);
        node.put("properties", properties);
        return node;
    }

    private Map<String, Object> edge(String source, String target, String type,
                                     String sourceType, String targetType) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceType", sourceType);
        properties.put("targetType", targetType);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        edge.put("type", type);
        edge.put("properties", properties);
        return edge;
    }
}
