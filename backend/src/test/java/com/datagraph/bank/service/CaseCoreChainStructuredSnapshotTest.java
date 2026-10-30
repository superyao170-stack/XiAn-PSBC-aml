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

    @Test
    void keepsAccountsAndOtherEntitiesInCaseScopeWithoutEventRelationships() throws Exception {
        var snapshot = json.readTree("""
                {"nodes":[
                  {"id":"CASE-1","type":"CASE"},
                  {"id":"ACC-1","type":"ACCOUNT","label":"62220001",
                   "properties":{"entity_id":"ACC-1","account_number":"62220001","holder_name":"张某"}},
                  {"id":"ENT-1","type":"ENTITY","label":"虚拟资产平台",
                   "properties":{"entity_id":"ENT-1","entity_type":"平台"}},
                  {"id":"ENT-2","type":"OTHER_ENTITY","label":"联网设备",
                   "properties":{"entity_type":"设备"}}
                ],"edges":[]}
                """);
        List<Map<String,Object>> nodes = new ArrayList<>(List.of(node("CASE-1", "CASE", "案例")));
        List<Map<String,Object>> edges = new ArrayList<>();

        service.appendStructuredSnapshotFacts("CASE-1", snapshot, List.of(), nodes,
                new LinkedHashSet<>(List.of("CASE-1")), edges);

        assertEquals(4, nodes.size());
        assertEquals(2, nodes.stream().filter(node -> "OTHER_ENTITY".equals(
                ((Map<?,?>) node.get("properties")).get("canonicalType"))).count());
        assertEquals("张某", ((Map<?,?>) nodes.get(1).get("properties")).get("holder_name"));
        assertEquals(3, edges.size());
        assertTrue(edges.stream().anyMatch(edge -> "ACC-1".equals(edge.get("target"))
                && "INVOLVES_ASSET".equals(edge.get("type"))));
        assertTrue(edges.stream().anyMatch(edge -> "ENT-1".equals(edge.get("target"))
                && "INVOLVES_ENTITY".equals(edge.get("type"))));
    }

    @Test
    void preservesOtherEntityEventRoleAndRelationshipsToAccounts() throws Exception {
        var snapshot = json.readTree("""
                {"nodes":[
                  {"id":"CASE-1","type":"CASE"},
                  {"id":"EVENT-1","type":"EVENT","label":"异常转账"},
                  {"id":"ACC-1","type":"ACCOUNT"},
                  {"id":"ENT-1","type":"ENTITY","label":"平台"}
                ],"edges":[
                  {"id":"R-1","source":"ENT-1","target":"EVENT-1","type":"涉及关系"},
                  {"id":"R-2","source":"ENT-1","target":"ACC-1","type":"关联关系"}
                ]}
                """);
        List<Map<String,Object>> nodes = new ArrayList<>(List.of(
                node("CASE-1", "CASE", "案例"), node("EVENT-1", "EVENT", "异常转账")));
        List<Map<String,Object>> edges = new ArrayList<>();

        service.appendStructuredSnapshotFacts("CASE-1", snapshot,
                List.of(Map.of("event_id", "EVENT-1", "event_name", "异常转账")), nodes,
                new LinkedHashSet<>(List.of("CASE-1", "EVENT-1")), edges);

        assertTrue(edges.stream().anyMatch(edge -> "EVENT-1".equals(edge.get("source"))
                && "ENT-1".equals(edge.get("target")) && "INVOLVES_ENTITY".equals(edge.get("type"))));
        assertTrue(edges.stream().anyMatch(edge -> "ENT-1".equals(edge.get("source"))
                && "ACC-1".equals(edge.get("target")) && "ASSOCIATED_WITH".equals(edge.get("type"))));
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
