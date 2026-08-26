package com.datagraph.bank.controller;

import com.datagraph.bank.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.entity.GraphEdge;
import com.datagraph.bank.entity.GraphNode;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.TuGraphStructuredWriter;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

@RestController
@RequestMapping("/api/v1/graph")
@CrossOrigin(origins = "*")
public class GraphController {

    private final Driver neo4jDriver;
    private final String activeEngine;
    private final Map<String, EngineEndpoint> engines;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;
    private final String graphDatabase;
    private final TuGraphStructuredWriter structuredWriter;

    public GraphController(
            @org.springframework.beans.factory.annotation.Value("${graph.active-engine:neo4j}") String activeEngine,
            @org.springframework.beans.factory.annotation.Value("${graph.neo4j.uri:bolt://localhost:7688}") String neo4jUri,
            @org.springframework.beans.factory.annotation.Value("${graph.neo4j.username:neo4j}") String neo4jUsername,
            @org.springframework.beans.factory.annotation.Value("${graph.neo4j.password:neo4j@123}") String neo4jPassword,
            @org.springframework.beans.factory.annotation.Value("${graph.tugraph.host:localhost}") String tugraphHost,
            @org.springframework.beans.factory.annotation.Value("${graph.tugraph.port:7687}") int tugraphPort,
            @org.springframework.beans.factory.annotation.Value("${graph.tugraph.username:admin}") String tugraphUsername,
            @org.springframework.beans.factory.annotation.Value("${graph.tugraph.password:tugraph@123}") String tugraphPassword,
            @org.springframework.beans.factory.annotation.Value("${graph.janusgraph.host:localhost}") String janusgraphHost,
            @org.springframework.beans.factory.annotation.Value("${graph.janusgraph.port:8182}") int janusgraphPort,
            @org.springframework.beans.factory.annotation.Value("${graph.tugraph.database:BankGraph}") String graphDatabase,
            JdbcTemplate jdbcTemplate, CurrentUser currentUser,
            TuGraphStructuredWriter structuredWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.graphDatabase = graphDatabase;
        this.structuredWriter = structuredWriter;
        this.activeEngine = activeEngine;
        this.engines = new LinkedHashMap<>();
        this.engines.put("neo4j", new EngineEndpoint(hostFromUri(neo4jUri), portFromUri(neo4jUri), "bolt"));
        this.engines.put("tugraph", new EngineEndpoint(tugraphHost, tugraphPort, "bolt"));
        this.engines.put("janusgraph", new EngineEndpoint(janusgraphHost, janusgraphPort, "gremlin"));
        if ("janusgraph".equalsIgnoreCase(activeEngine)) {
            throw new IllegalArgumentException("JanusGraph 使用 Gremlin 协议，当前查询端点仅支持 Bolt 主引擎");
        }
        String activeUri = "tugraph".equalsIgnoreCase(activeEngine)
                ? "bolt://" + tugraphHost + ":" + tugraphPort : neo4jUri;
        String activeUsername = "tugraph".equalsIgnoreCase(activeEngine) ? tugraphUsername : neo4jUsername;
        String activePassword = "tugraph".equalsIgnoreCase(activeEngine) ? tugraphPassword : neo4jPassword;
        this.neo4jDriver = GraphDatabase.driver(activeUri, AuthTokens.basic(activeUsername, activePassword));
    }

    @GetMapping("/nodes")
    public CommonResult<List<GraphNode>> getNodes(
            @RequestParam(required = false) String label,
            @RequestParam(defaultValue = "50") int limit) {
        List<GraphNode> nodes = new ArrayList<>();
        if (label != null && !label.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return CommonResult.error(400, "Invalid graph label");
        }
        String where = currentUser.isBankAdmin() ? " WHERE n.bankCode = $bankCode " : " ";
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String query = label != null
                ? "MATCH (n:" + label + ")" + where + "RETURN n LIMIT " + safeLimit
                : "MATCH (n)" + where + "RETURN n LIMIT " + safeLimit;

        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result result = session.run(query, Values.parameters(
                    "bankCode", currentUser.isBankAdmin() ? currentUser.requiredBankCode() : ""));
            while (result.hasNext()) {
                Record record = result.next();
                org.neo4j.driver.types.Node node = record.get("n").asNode();
                nodes.add(convertNode(node));
            }
        } catch (Exception e) {
            return CommonResult.error(500, "查询节点失败: " + e.getMessage());
        }
        return CommonResult.success(nodes);
    }

    @GetMapping("/edges")
    public CommonResult<List<GraphEdge>> getEdges(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit) {
        List<GraphEdge> edges = new ArrayList<>();
        if (type != null && !type.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return CommonResult.error(400, "Invalid relationship type");
        }
        String where = currentUser.isBankAdmin()
                ? " WHERE a.bankCode = $bankCode AND b.bankCode = $bankCode " : " ";
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String query = type != null
                ? "MATCH (a)-[r:" + type + "]->(b)" + where + "RETURN r LIMIT " + safeLimit
                : "MATCH (a)-[r]->(b)" + where + "RETURN r LIMIT " + safeLimit;

        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result result = session.run(query, Values.parameters(
                    "bankCode", currentUser.isBankAdmin() ? currentUser.requiredBankCode() : ""));
            while (result.hasNext()) {
                Record record = result.next();
                org.neo4j.driver.types.Relationship rel = record.get("r").asRelationship();
                edges.add(convertEdge(rel));
            }
        } catch (Exception e) {
            return CommonResult.error(500, "查询边失败: " + e.getMessage());
        }
        return CommonResult.success(edges);
    }

    @PostMapping("/query")
    public CommonResult<Map<String, Object>> executeQuery(@RequestBody GraphQueryRequest request) {
        if (currentUser.isBankAdmin()) {
            return CommonResult.error(403, "Raw Cypher is restricted to system administrators");
        }
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        String queryId = "GQL-" + UUID.randomUUID();
        long started = System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO graph_query_log
                (query_id, bank_code, engine_type, query_type, query_text, status)
                VALUES (?, ?, ?, 'CYPHER', ?, 'EXECUTING')
                """, queryId, currentUser.principal().bankCode() == null ? "SYSTEM" : currentUser.principal().bankCode(),
                activeEngine, request.getCypher());

        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result queryResult = session.run(request.getCypher());
            List<String> keys = queryResult.keys();
            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                Map<String, Object> row = new HashMap<>();
                for (String key : keys) {
                    if (record.containsKey(key)) {
                        row.put(key, convertValue(record.get(key)));
                    }
                }
                records.add(row);
            }
            result.put("keys", keys);
            result.put("records", records);
            jdbcTemplate.update("""
                    UPDATE graph_query_log SET status = 'SUCCEEDED', execution_time_ms = ?,
                        result_count = ? WHERE query_id = ?
                    """, System.currentTimeMillis() - started, records.size(), queryId);
        } catch (Exception e) {
            jdbcTemplate.update("""
                    UPDATE graph_query_log SET status = 'FAILED', execution_time_ms = ?,
                        error_message = ? WHERE query_id = ?
                    """, System.currentTimeMillis() - started, e.getMessage(), queryId);
            return CommonResult.error(500, "执行查询失败: " + e.getMessage());
        }
        return CommonResult.success(result);
    }

    @GetMapping("/subgraph")
    public CommonResult<Map<String, Object>> getSubgraph(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "2") int depth) {
        Map<String, Object> subgraph = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            int safeDepth = Math.min(Math.max(depth, 1), 5);
            String bankScope = currentUser.isBankAdmin()
                    ? " AND n.bankCode = $bankCode AND m.bankCode = $bankCode " : " ";
            String query = "MATCH (n)-[r*1.." + safeDepth + "]-(m) WHERE id(n) = $nodeId"
                    + bankScope + "RETURN n, r, m";
            Result result = session.run(query, Values.parameters(
                    "nodeId", Long.parseLong(nodeId),
                    "bankCode", currentUser.isBankAdmin() ? currentUser.requiredBankCode() : ""));
            
            Set<String> visitedNodes = new HashSet<>();
            
            while (result.hasNext()) {
                Record record = result.next();
                org.neo4j.driver.types.Node startNode = record.get("n").asNode();
                if (!visitedNodes.contains(startNode.elementId())) {
                    nodes.add(convertNode(startNode));
                    visitedNodes.add(startNode.elementId());
                }
                
                if (record.containsKey("m")) {
                    org.neo4j.driver.types.Node endNode = record.get("m").asNode();
                    if (!visitedNodes.contains(endNode.elementId())) {
                        nodes.add(convertNode(endNode));
                        visitedNodes.add(endNode.elementId());
                    }
                }
                
                if (record.containsKey("r")) {
                    Object relObj = record.get("r").asObject();
                    if (relObj instanceof List) {
                        for (Object item : (List<?>) relObj) {
                            if (item instanceof org.neo4j.driver.types.Relationship) {
                                edges.add(convertEdge((org.neo4j.driver.types.Relationship) item));
                            }
                        }
                    } else if (relObj instanceof org.neo4j.driver.types.Relationship) {
                        edges.add(convertEdge((org.neo4j.driver.types.Relationship) relObj));
                    }
                }
            }
        } catch (Exception e) {
            return CommonResult.error(500, "获取子图失败: " + e.getMessage());
        }

        subgraph.put("nodes", nodes);
        subgraph.put("edges", edges);
        return CommonResult.success(subgraph);
    }

    @GetMapping("/case-subgraph")
    @Operation(
            summary = "6. 展示案例图谱",
            description = "按案例 ID 返回已持久化的节点与边，用于识别结果的图谱展示。响应包含 caseId、nodes、edges、engine 和 database。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG)
    public CommonResult<Map<String, Object>> getCaseSubgraph(
            @Parameter(description = "识别任务固化后的案例 ID", required = true, example = "CASE-2026-001")
            @RequestParam String caseId) {
        if (caseId == null || !caseId.matches("[A-Za-z0-9_-]+")) {
            return CommonResult.error(400, "Invalid case id");
        }
        List<Map<String, Object>> activeCases = jdbcTemplate.queryForList("""
                SELECT case_id,bank_code FROM cf_risk_case
                WHERE case_id=? AND deleted=false
                """, caseId);
        if (activeCases.isEmpty()) {
            return CommonResult.error(404, "案例不存在或已删除");
        }
        currentUser.requireAccessToBank(Objects.toString(activeCases.get(0).get("bank_code")));
        Map<String, Object> subgraph = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        String nodeScope = " WHERE n.graphId=$caseId ";
        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result nr = session.run("MATCH (n:Case)" + nodeScope + "RETURN n LIMIT 10",
                    Values.parameters("caseId", caseId));
            List<String> nodeIds = new ArrayList<>();
            while (nr.hasNext()) {
                var node = nr.next().get("n").asNode();
                nodes.add(convertNode(node));
                nodeIds.add(node.elementId());
            }
            Result er = session.run("""
                    MATCH (c:Case)-[r]-(n)
                    WHERE c.graphId=$caseId
                    RETURN c,r,n LIMIT 5000""", Values.parameters("caseId", caseId));
            Set<String> seen = new HashSet<>();
            while (er != null && er.hasNext()) {
                Record row = er.next();
                addNodeIfMissing(nodes, row.get("c").asNode());
                addNodeIfMissing(nodes, row.get("n").asNode());
                GraphEdge edge = convertEdge(row.get("r").asRelationship());
                String key = edge.getType() + ":" + edge.getSource() + ":" + edge.getTarget();
                if (seen.add(key)) edges.add(edge);
            }
            Result deeper = session.run("""
                    MATCH (c:Case)-[]-(n)-[r]-(m)
                    WHERE c.graphId=$caseId
                      AND (m.caseId IS NULL OR m.caseId='' OR m.caseId=$caseId)
                    RETURN n,r,m LIMIT 5000""", Values.parameters("caseId", caseId));
            while (deeper.hasNext()) {
                Record row = deeper.next();
                addNodeIfMissing(nodes, row.get("n").asNode());
                addNodeIfMissing(nodes, row.get("m").asNode());
                GraphEdge edge = convertEdge(row.get("r").asRelationship());
                String key = edge.getType() + ":" + edge.getSource() + ":" + edge.getTarget();
                if (seen.add(key)) edges.add(edge);
            }
            Result third = session.run("""
                    MATCH (c:Case)-[]-(n)-[]-(m)-[r]-(x)
                    WHERE c.graphId=$caseId
                      AND (x.caseId IS NULL OR x.caseId='' OR x.caseId=$caseId)
                    RETURN m,r,x LIMIT 5000""", Values.parameters("caseId", caseId));
            while (third.hasNext()) {
                Record row = third.next();
                addNodeIfMissing(nodes, row.get("m").asNode());
                addNodeIfMissing(nodes, row.get("x").asNode());
                GraphEdge edge = convertEdge(row.get("r").asRelationship());
                String key = edge.getType() + ":" + edge.getSource() + ":" + edge.getTarget();
                if (seen.add(key)) edges.add(edge);
            }
            Result scoped = session.run("""
                    MATCH (a)-[r]-(b)
                    WHERE a.caseId=$caseId AND b.caseId=$caseId
                    RETURN a,r,b LIMIT 5000""", Values.parameters("caseId", caseId));
            while (scoped.hasNext()) {
                Record row = scoped.next();
                addNodeIfMissing(nodes, row.get("a").asNode());
                addNodeIfMissing(nodes, row.get("b").asNode());
                GraphEdge edge = convertEdge(row.get("r").asRelationship());
                String key = edge.getType() + ":" + edge.getSource() + ":" + edge.getTarget();
                if (seen.add(key)) edges.add(edge);
            }
        } catch (Exception e) {
            return CommonResult.error(500, "获取案例子图失败: " + e.getMessage());
        }
        // V1.8 response boundary also protects callers while historical TuGraph
        // projections are being rebuilt: auxiliary/knowledge/narrative nodes
        // never leak into the case graph API.
        nodes.removeIf(node -> !isV18CaseGraphNode(node));
        nodes.forEach(this::applyInstanceContract);
        Set<String> retainedNodeIds = nodes.stream()
                .map(GraphNode::getId).collect(java.util.stream.Collectors.toSet());
        edges.removeIf(edge -> !retainedNodeIds.contains(edge.getSource())
                || !retainedNodeIds.contains(edge.getTarget()));
        subgraph.put("caseId", caseId);
        subgraph.put("nodes", nodes);
        subgraph.put("edges", edges);
        subgraph.put("engine", activeEngine);
        subgraph.put("database", graphDatabase);
        return CommonResult.success(subgraph);
    }

    private void addNodeIfMissing(List<GraphNode> nodes, org.neo4j.driver.types.Node node) {
        if (nodes.stream().noneMatch(existing -> Objects.equals(existing.getId(), node.elementId()))) {
            nodes.add(convertNode(node));
        }
    }

    private boolean isV18CaseGraphNode(GraphNode node) {
        Set<String> allowed = Set.of(
                "CASE", "CUSTOMER", "ORGANIZATION", "MERCHANT", "ACCOUNT", "WALLET",
                "DEVICE", "IP_ADDRESS", "ADDRESS", "EVIDENCE", "EVENT",
                "INDICATOR_RESULT", "BEHAVIOR_PATTERN", "RISK_HYPOTHESIS",
                "INVESTIGATION_HYPOTHESIS");
        String nodeType = Objects.toString(node.getProperties().get("nodeType"), "")
                .replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
        String instanceId = Objects.toString(node.getProperties().get("graphId"), "");
        String caseId = Objects.toString(node.getProperties().get("caseId"), "");
        return allowed.contains(nodeType) && !instanceId.isBlank() && !caseId.isBlank();
    }

    private void applyInstanceContract(GraphNode node) {
        Map<String, Object> properties = new LinkedHashMap<>(node.getProperties());
        String nodeType = Objects.toString(properties.get("nodeType"), "")
                .replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
        properties.put("instanceId", Objects.toString(properties.get("graphId"), node.getId()));
        properties.put("nodeType", nodeType);
        properties.put("canonicalType", nodeType);
        properties.put("objectSemantics", "INSTANCE");
        properties.put("graphDomain", Set.of("INDICATOR_RESULT", "BEHAVIOR_PATTERN",
                "RISK_HYPOTHESIS", "INVESTIGATION_HYPOTHESIS").contains(nodeType)
                ? "REASONING_GRAPH" : "EVENT_GRAPH");
        properties.put("physicalLabel", node.getLabel());
        node.setProperties(properties);
    }

    @PostMapping("/case-rebuild")
    public CommonResult<Map<String,Object>> rebuildCaseGraph(@RequestBody Map<String,Object> request) {
        String caseId = Objects.toString(request.get("caseId"), "");
        if (!caseId.matches("[A-Za-z0-9_-]+")) return CommonResult.error(400, "Invalid case id");
        if (currentUser.isBankAdmin()) {
            Integer owned = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cf_risk_case WHERE case_id=? AND bank_code=? AND deleted=false",
                    Integer.class, caseId, currentUser.requiredBankCode());
            if (owned == null || owned == 0) return CommonResult.error(403, "Case is outside the current bank");
        }
        String source = jdbcTemplate.queryForObject(
                "SELECT case_source FROM cf_risk_case WHERE case_id=? AND deleted=false",
                String.class, caseId);
        return CommonResult.success("TEXT_CASE".equalsIgnoreCase(source)
                ? structuredWriter.rebuildTextCase(caseId)
                : structuredWriter.rebuildCase(caseId));
    }

    @PostMapping("/case-fallback")
    public CommonResult<Map<String,Object>> createCaseFallback(@RequestBody Map<String,Object> request) {
        String caseId = String.valueOf(request.getOrDefault("caseId", ""));
        if (!caseId.matches("[A-Za-z0-9_-]+")) return CommonResult.error(400, "Invalid case id");
        int created = 0;
        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            String q = "MATCH (c),(n) WHERE (c.caseId=$caseId OR c.graphId=$caseId) AND ((n.caseId=$caseId OR n.graphId=$caseId)) " +
                    "AND ((toLower(labels(n)[0])='evidence' AND NOT (c)-[:CONTAINS_EVIDENCE]->(n)) OR (toLower(labels(n)[0])='event' AND NOT (c)-[:CONTAINS_EVENT]->(n))) " +
                    "WITH c,n,CASE WHEN toLower(labels(n)[0])='evidence' THEN 'CONTAINS_EVIDENCE' ELSE 'CONTAINS_EVENT' END AS rel " +
                    "CALL apoc.create.relationship(c,rel,{},n) YIELD rel AS r RETURN count(r) AS created";
            Result result = session.run(q, Values.parameters("caseId", caseId));
            if (result.hasNext()) created = result.single().get("created").asInt();
        } catch (Exception e) { return CommonResult.error(500, "创建归属兜底关系失败: " + e.getMessage()); }
        return CommonResult.success(Map.of("caseId", caseId, "created", created));
    }

    @GetMapping("/schema")
    public CommonResult<Map<String, Object>> getSchema() {
        Map<String, Object> schema = new HashMap<>();
        List<Map<String, Object>> nodeLabels = new ArrayList<>();
        List<Map<String, Object>> edgeTypes = new ArrayList<>();

        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result nodeResult = session.run("CALL db.vertexLabels()");
            while (nodeResult.hasNext()) {
                Map<String, Object> label = new HashMap<>();
                label.put("name", nodeResult.next().get("label").asString());
                nodeLabels.add(label);
            }

            Result relResult = session.run("CALL db.edgeLabels()");
            while (relResult.hasNext()) {
                Map<String, Object> type = new HashMap<>();
                type.put("name", relResult.next().get("label").asString());
                edgeTypes.add(type);
            }
        } catch (Exception e) {
            return CommonResult.error(500, "获取图Schema失败: " + e.getMessage());
        }

        schema.put("nodeLabels", nodeLabels);
        schema.put("edgeTypes", edgeTypes);
        return CommonResult.success(schema);
    }

    @PostMapping("/nodes")
    public CommonResult<GraphNode> createNode(@RequestBody GraphNodeRequest request) {
        if (request.getLabel() == null || !request.getLabel().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return CommonResult.error(400, "Invalid graph label");
        }
        Map<String, Object> safeProperties = request.getProperties() == null
                ? new HashMap<>() : new HashMap<>(request.getProperties());
        for (String key : safeProperties.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return CommonResult.error(400, "Invalid graph property");
            }
        }
        if (currentUser.isBankAdmin()) safeProperties.put("bankCode", currentUser.requiredBankCode());
        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            StringBuilder query = new StringBuilder("CREATE (n:" + request.getLabel());
            if (!safeProperties.isEmpty()) {
                query.append(" {");
                List<String> props = new ArrayList<>();
                for (Map.Entry<String, Object> entry : safeProperties.entrySet()) {
                    props.add(entry.getKey() + ": $" + entry.getKey());
                }
                query.append(String.join(", ", props));
                query.append("}");
            }
            query.append(") RETURN n");

            Map<String, Object> params = new HashMap<>();
            params.putAll(safeProperties);

            Result result = session.run(query.toString(), params);
            if (result.hasNext()) {
                org.neo4j.driver.types.Node node = result.next().get("n").asNode();
                return CommonResult.success(convertNode(node));
            }
        } catch (Exception e) {
            return CommonResult.error(500, "创建节点失败: " + e.getMessage());
        }
        return CommonResult.error(500, "创建节点失败");
    }

    @PostMapping("/edges")
    public CommonResult<GraphEdge> createEdge(@RequestBody GraphEdgeRequest request) {
        if (request.getType() == null || !request.getType().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return CommonResult.error(400, "Invalid relationship type");
        }
        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            String bankScope = currentUser.isBankAdmin()
                    ? " AND a.bankCode = $bankCode AND b.bankCode = $bankCode " : " ";
            String query = "MATCH (a), (b) WHERE id(a) = $sourceId AND id(b) = $targetId " + bankScope +
                    "CREATE (a)-[r:" + request.getType() + "]->(b) RETURN r";

            Result result = session.run(query, Values.parameters(
                    "sourceId", Long.parseLong(request.getSource()),
                    "targetId", Long.parseLong(request.getTarget()),
                    "bankCode", currentUser.isBankAdmin() ? currentUser.requiredBankCode() : ""
            ));

            if (result.hasNext()) {
                org.neo4j.driver.types.Relationship rel = result.next().get("r").asRelationship();
                return CommonResult.success(convertEdge(rel));
            }
        } catch (Exception e) {
            return CommonResult.error(500, "创建边失败: " + e.getMessage());
        }
        return CommonResult.error(500, "创建边失败");
    }

    @GetMapping("/health")
    public CommonResult<Map<String, Object>> healthCheck() {
        try (Session session = neo4jDriver.session(SessionConfig.forDatabase(graphDatabase))) {
            Result result = session.run("RETURN 1");
            if (result.hasNext()) {
                return CommonResult.success(Map.of("activeEngine", activeEngine, "status", "UP"));
            }
        } catch (Exception e) {
            return CommonResult.error(500, "图数据库连接异常: " + e.getMessage());
        }
        return CommonResult.error(500, "图数据库连接异常");
    }

    @GetMapping("/engines")
    public CommonResult<Map<String, Object>> engineStatus() {
        List<Map<String, Object>> status = new ArrayList<>();
        engines.forEach((name, endpoint) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("active", name.equalsIgnoreCase(activeEngine));
            item.put("protocol", endpoint.protocol());
            item.put("host", endpoint.host());
            item.put("port", endpoint.port());
            item.put("status", isReachable(endpoint.host(), endpoint.port()) ? "UP" : "DOWN");
            status.add(item);
        });
        return CommonResult.success(Map.of("activeEngine", activeEngine, "engines", status));
    }

    private boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String hostFromUri(String uri) {
        String value = uri.replaceFirst("^[a-zA-Z]+://", "");
        int colon = value.lastIndexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    private static int portFromUri(String uri) {
        String value = uri.replaceFirst("^[a-zA-Z]+://", "");
        int colon = value.lastIndexOf(':');
        return colon > 0 ? Integer.parseInt(value.substring(colon + 1)) : 7687;
    }

    private record EngineEndpoint(String host, int port, String protocol) {}

    private GraphNode convertNode(org.neo4j.driver.types.Node node) {
        Map<String, Object> properties = new HashMap<>();
        for (String key : node.keys()) {
            properties.put(key, convertValue(node.get(key)));
        }
        return new GraphNode(node.elementId(), String.join(",", node.labels()), properties);
    }

    private GraphEdge convertEdge(org.neo4j.driver.types.Relationship rel) {
        Map<String, Object> properties = new HashMap<>();
        for (String key : rel.keys()) {
            properties.put(key, convertValue(rel.get(key)));
        }
        return new GraphEdge(rel.elementId(), rel.type(), 
                rel.startNodeElementId(), rel.endNodeElementId(), properties);
    }

    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return value.asNumber();
        } catch (Exception e) {}
        try {
            return value.asBoolean();
        } catch (Exception e) {}
        try {
            return value.asString();
        } catch (Exception e) {}
        try {
            List<Object> list = new ArrayList<>();
            for (Object item : value.asList()) {
                if (item instanceof Value) {
                    list.add(convertValue((Value) item));
                } else {
                    list.add(item);
                }
            }
            return list;
        } catch (Exception e) {}
        try {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.asMap().keySet()) {
                map.put(key, convertValue(value.get(key)));
            }
            return map;
        } catch (Exception e) {}
        return value.asString();
    }

    public static class GraphQueryRequest {
        private String cypher;

        public String getCypher() { return cypher; }
        public void setCypher(String cypher) { this.cypher = cypher; }
    }

    public static class GraphNodeRequest {
        private String label;
        private Map<String, Object> properties;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }

    public static class GraphEdgeRequest {
        private String type;
        private String source;
        private String target;
        private Map<String, Object> properties;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }
}
