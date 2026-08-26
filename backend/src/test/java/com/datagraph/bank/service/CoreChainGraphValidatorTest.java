package com.datagraph.bank.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreChainGraphValidatorTest {
    private final CoreChainGraphValidator validator = new CoreChainGraphValidator();

    @Test
    void keepsOnlyTheCaseConnectedComponent() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("CASE-1"), node("EVENT-1"), node("PATTERN-X"), node("DEF-X")));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                edge("CASE-1", "CONTAINS_EVENT", "EVENT-1"),
                edge("PATTERN-X", "INSTANCE_OF_PATTERN", "DEF-X")));
        List<Map<String, Object>> issues = new ArrayList<>();

        CoreChainGraphValidator.ValidationResult result =
                validator.close("CASE-1", nodes, edges, issues);

        assertEquals(List.of("CASE-1", "EVENT-1"),
                nodes.stream().map(node -> node.get("id")).toList());
        assertEquals(1, edges.size());
        assertEquals(2, result.disconnectedNodeCount());
        assertTrue(issues.stream().anyMatch(issue -> "CC_NODE_002".equals(issue.get("code"))));
    }

    @Test
    void rejectsZeroDegreeNodesAndDanglingEdges() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("CASE-1"), node("EVENT-1"), node("ORPHAN")));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                edge("CASE-1", "CONTAINS_EVENT", "EVENT-1"),
                edge("MISSING", "SUPPORTS", "EVENT-1")));
        List<Map<String, Object>> issues = new ArrayList<>();

        CoreChainGraphValidator.ValidationResult result =
                validator.close("CASE-1", nodes, edges, issues);

        assertEquals(1, result.isolatedNodeCount());
        assertEquals(1, result.danglingEdgeCount());
        assertEquals(2, nodes.size());
        assertEquals(1, edges.size());
        assertTrue(issues.stream().anyMatch(issue -> "CC_NODE_001".equals(issue.get("code"))));
        assertTrue(issues.stream().anyMatch(issue -> "CC_REF_001".equals(issue.get("code"))));
    }

    @Test
    void rejectsAnEntireGraphWhenTheCaseIsIsolated() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(node("CASE-1")));
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();

        CoreChainGraphValidator.ValidationResult result =
                validator.close("CASE-1", nodes, edges, issues);

        assertEquals(1, result.isolatedNodeCount());
        assertTrue(nodes.isEmpty());
        assertTrue(edges.isEmpty());
    }

    @Test
    void removesDuplicateAndSelfLoopEdges() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(node("CASE-1"), node("EVENT-1")));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                edge("CASE-1", "CONTAINS_EVENT", "EVENT-1"),
                edge("CASE-1", "CONTAINS_EVENT", "EVENT-1"),
                edge("EVENT-1", "SELF", "EVENT-1")));
        List<Map<String, Object>> issues = new ArrayList<>();

        CoreChainGraphValidator.ValidationResult result =
                validator.close("CASE-1", nodes, edges, issues);

        assertEquals(1, result.duplicateEdgeCount());
        assertEquals(1, result.selfLoopCount());
        assertEquals(1, edges.size());
        assertEquals(2, nodes.size());
    }

    @Test
    void rejectsFactNodeThatIsOnlyAttachedToCaseScope() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                typedNode("CASE-1", "CASE"),
                typedNode("EVENT-1", "EVENT"),
                typedNode("ACCOUNT-1", "ACCOUNT")));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                edge("CASE-1", "CONTAINS_EVENT", "EVENT-1"),
                edge("CASE-1", "CONTAINS_ACCOUNT", "ACCOUNT-1")));
        List<Map<String, Object>> issues = new ArrayList<>();

        CoreChainGraphValidator.ValidationResult result =
                validator.close("CASE-1", nodes, edges, issues);

        assertEquals(1, result.semanticOrphanNodeCount());
        assertEquals(List.of("CASE-1", "EVENT-1"),
                nodes.stream().map(node -> node.get("id")).toList());
        assertTrue(issues.stream().anyMatch(issue -> "CC_NODE_003".equals(issue.get("code"))));
    }

    private Map<String, Object> node(String id) {
        String type = id.startsWith("CASE") ? "CASE"
                : id.startsWith("EVENT") ? "EVENT"
                : id.startsWith("PATTERN") ? "BEHAVIOR_PATTERN"
                : "EVIDENCE";
        return typedNode(id, type);
    }

    private Map<String, Object> typedNode(String id, String type) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "properties", new LinkedHashMap<>(Map.of(
                        "canonicalType", type,
                        "nodeType", type,
                        "instanceId", id,
                        "caseId", "CASE-1",
                        "objectSemantics", "INSTANCE"))));
    }

    private Map<String, Object> edge(String source, String relation, String target) {
        return new LinkedHashMap<>(Map.of(
                "id", source + "-" + relation + "-" + target,
                "source", source,
                "type", relation,
                "target", target));
    }
}
