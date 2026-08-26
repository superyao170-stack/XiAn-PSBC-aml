package com.datagraph.bank.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closes a candidate core-chain graph before it is returned or frozen.
 *
 * <p>A core-chain node is valid only when it participates in at least one
 * relation and belongs to the undirected component rooted at the case node.
 * This keeps zero-degree nodes and internally connected but case-disconnected
 * fragments out of the effective graph.</p>
 */
final class CoreChainGraphValidator {
    private static final Set<String> INSTANCE_NODE_TYPES = Set.of(
            "CASE", "CUSTOMER", "ORGANIZATION", "MERCHANT", "ACCOUNT", "WALLET",
            "DEVICE", "IP_ADDRESS", "ADDRESS", "EVIDENCE", "EVENT",
            "INDICATOR_RESULT", "BEHAVIOR_PATTERN", "RISK_HYPOTHESIS",
            "INVESTIGATION_HYPOTHESIS");

    ValidationResult close(String rootNodeId,
                           List<Map<String, Object>> nodes,
                           List<Map<String, Object>> edges,
                           List<Map<String, Object>> issues) {
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        Set<String> contractRejectedNodeIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id.isBlank()) continue;
            if (!validInstanceContract(node)) {
                contractRejectedNodeIds.add(id);
                issues.add(issue("CC_INSTANCE_001", "ERROR", id,
                        "节点未满足实例契约（正式类型、实例ID、案例归属或INSTANCE语义缺失），已剔除。"));
            } else {
                nodeById.putIfAbsent(id, node);
            }
        }
        nodes.removeIf(node -> contractRejectedNodeIds.contains(text(node.get("id"))));

        int danglingEdges = 0;
        int duplicateEdges = 0;
        int selfLoops = 0;
        Set<String> edgeKeys = new LinkedHashSet<>();
        List<Map<String, Object>> validEdges = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            String relation = text(edge.get("type"));
            String edgeKey = source + "|" + relation + "|" + target;
            if (!nodeById.containsKey(source) || !nodeById.containsKey(target)) {
                danglingEdges++;
                issues.add(issue("CC_REF_001", "ERROR", text(edge.get("id")),
                        "边引用了不存在的源节点或目标节点，已从有效核心链剔除。"));
            } else if (source.equals(target)) {
                selfLoops++;
                issues.add(issue("CC_EDGE_002", "ERROR", text(edge.get("id")),
                        "核心链不允许自环边，已从有效核心链剔除。"));
            } else if (!edgeKeys.add(edgeKey)) {
                duplicateEdges++;
                issues.add(issue("CC_EDGE_001", "WARNING", text(edge.get("id")),
                        "重复关系已合并，仅保留一条有效边。"));
            } else {
                validEdges.add(edge);
            }
        }

        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        nodeById.keySet().forEach(id -> adjacency.put(id, new LinkedHashSet<>()));
        for (Map<String, Object> edge : validEdges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            adjacency.get(source).add(target);
            adjacency.get(target).add(source);
        }

        Set<String> reachable = new HashSet<>();
        if (nodeById.containsKey(rootNodeId)) {
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(rootNodeId);
            reachable.add(rootNodeId);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                for (String next : adjacency.getOrDefault(current, Set.of())) {
                    if (reachable.add(next)) queue.addLast(next);
                }
            }
        }

        Set<String> rejectedNodeIds = new LinkedHashSet<>();
        int isolatedNodes = 0;
        int disconnectedNodes = 0;
        int semanticOrphanNodes = 0;
        Set<String> eventReachable = eventSemanticReachable(nodeById, adjacency);
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id.isBlank()) continue;
            if (adjacency.getOrDefault(id, Set.of()).isEmpty()) {
                isolatedNodes++;
                rejectedNodeIds.add(id);
                issues.add(issue("CC_NODE_001", "ERROR", id,
                        "节点没有任何有效关系，是孤立节点，已从有效核心链剔除。"));
            } else if (!reachable.contains(id)) {
                disconnectedNodes++;
                rejectedNodeIds.add(id);
                issues.add(issue("CC_NODE_002", "ERROR", id,
                        "节点虽有局部关系但无法连通到 Case，已从有效核心链剔除。"));
            } else if (requiresEventReachability(node) && !eventReachable.contains(id)) {
                semanticOrphanNodes++;
                rejectedNodeIds.add(id);
                issues.add(issue("CC_NODE_003", "ERROR", id,
                        "事实节点仅通过案例归属挂入，无法沿有来源的事实关系到达 Event，已剔除。"));
            }
        }

        nodes.removeIf(node -> rejectedNodeIds.contains(text(node.get("id"))));
        Set<String> retainedNodeIds = new HashSet<>();
        nodes.forEach(node -> retainedNodeIds.add(text(node.get("id"))));
        validEdges.removeIf(edge -> !retainedNodeIds.contains(text(edge.get("source")))
                || !retainedNodeIds.contains(text(edge.get("target"))));
        edges.clear();
        edges.addAll(validEdges);

        return new ValidationResult(isolatedNodes, disconnectedNodes, semanticOrphanNodes, danglingEdges,
                duplicateEdges, selfLoops, contractRejectedNodeIds.size());
    }

    private boolean validInstanceContract(Map<String, Object> node) {
        if (!(node.get("properties") instanceof Map<?, ?> properties)) return false;
        String canonicalType = text(properties.get("canonicalType"));
        return INSTANCE_NODE_TYPES.contains(canonicalType)
                && canonicalType.equals(text(properties.get("nodeType")))
                && "INSTANCE".equals(text(properties.get("objectSemantics")))
                && !text(properties.get("instanceId")).isBlank()
                && !text(properties.get("caseId")).isBlank();
    }

    private Set<String> eventSemanticReachable(Map<String, Map<String, Object>> nodeById,
                                               Map<String, Set<String>> adjacency) {
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        nodeById.forEach((id, node) -> {
            if ("EVENT".equals(canonicalType(node))) {
                reached.add(id);
                queue.add(id);
            }
        });
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if ("CASE".equals(canonicalType(nodeById.get(next)))) continue;
                if (reached.add(next)) queue.addLast(next);
            }
        }
        return reached;
    }

    private boolean requiresEventReachability(Map<String, Object> node) {
        return Set.of("CUSTOMER", "ORGANIZATION", "MERCHANT", "ACCOUNT", "WALLET",
                "DEVICE", "IP_ADDRESS", "ADDRESS", "EVIDENCE").contains(canonicalType(node));
    }

    private String canonicalType(Map<String, Object> node) {
        if (node == null || !(node.get("properties") instanceof Map<?, ?> properties)) return "";
        return text(properties.get("canonicalType"));
    }

    private Map<String, Object> issue(String code, String severity,
                                      String nodeKey, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("severity", severity);
        result.put("nodeKey", nodeKey);
        result.put("message", message);
        return result;
    }

    private String text(Object value) {
        return Objects.toString(value, "");
    }

    record ValidationResult(int isolatedNodeCount,
                            int disconnectedNodeCount,
                            int semanticOrphanNodeCount,
                            int danglingEdgeCount,
                            int duplicateEdgeCount,
                            int selfLoopCount,
                            int instanceContractViolationCount) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("isolatedNodeCount", isolatedNodeCount);
            result.put("disconnectedNodeCount", disconnectedNodeCount);
            result.put("semanticOrphanNodeCount", semanticOrphanNodeCount);
            result.put("danglingEdgeCount", danglingEdgeCount);
            result.put("duplicateEdgeCount", duplicateEdgeCount);
            result.put("selfLoopCount", selfLoopCount);
            result.put("instanceContractViolationCount", instanceContractViolationCount);
            result.put("isolatePolicy", "REJECT");
            result.put("objectSemantics", "INSTANCE");
            return result;
        }
    }
}
