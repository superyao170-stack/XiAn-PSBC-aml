package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bank-local task 2/3 analytics scope. A case is an optional filter/member of
 * the scope, never the reasoning boundary. Shared entity identifiers are kept
 * across case envelopes so cross-case event and matter paths become connected.
 */
@Service
public class Task23ScopeAnalyticsService {
    private static final Set<String> EVENT_TYPES = Set.of(
            "EVENT_CHAIN", "BEHAVIOR_MATRIX", "TEMPORAL_ANOMALY");
    private static final Set<String> ENVELOPE_TYPES = Set.of(
            "META_PATH_DETECTION", "LOCAL_HYPERGRAPH");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "EVENT_CHAIN", "BEHAVIOR_MATRIX", "RISK_DIFFUSION",
            "INCREMENTAL_LEARNING", "META_PATH_DETECTION",
            "TEMPORAL_ANOMALY", "LOCAL_HYPERGRAPH", "CASCADE_INFERENCE");

    private final JdbcTemplate jdbc;
    private final Task23GraphAnalyticsService caseAnalytics;
    private final VersionedContractService contracts;
    private final ObjectMapper mapper;

    public Task23ScopeAnalyticsService(JdbcTemplate jdbc,
                                       Task23GraphAnalyticsService caseAnalytics,
                                       VersionedContractService contracts,
                                       ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.caseAnalytics = caseAnalytics;
        this.contracts = contracts;
        this.mapper = mapper;
    }

    public Map<String, Object> context(String bankCode, ScopeFilter filter) {
        ScopeFilter normalized = normalize(filter);
        List<Map<String, Object>> cases = resolveCases(bankCode, normalized);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("当前银行分析范围内没有案件");
        }
        List<Map<String, Object>> caseContexts = new ArrayList<>();
        long events = 0;
        long nodes = 0;
        long edges = 0;
        long matters = 0;
        Map<String, Long> scenarios = new LinkedHashMap<>();
        for (Map<String, Object> member : cases) {
            String caseId = Objects.toString(member.get("caseId"));
            Map<String, Object> value = caseAnalytics.context(caseId);
            caseContexts.add(value);
            events += number(value.get("eventCount"));
            nodes += number(value.get("graphNodeCount"));
            edges += number(value.get("graphEdgeCount"));
            matters += number(value.get("matterCount"));
            scenarios.merge(Objects.toString(member.get("scenarioCode"), "UNKNOWN"), 1L, Long::sum);
        }
        String scopeId = scopeId(bankCode, normalized, cases);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeId", scopeId);
        result.put("scopeType", "LOCAL_BANK");
        result.put("bankCode", bankCode);
        result.put("scenarioCode", normalized.scenarioCode());
        result.put("startTime", normalized.startTime());
        result.put("endTime", normalized.endTime());
        result.put("caseCount", cases.size());
        result.put("eventCount", events);
        result.put("graphNodeCount", nodes);
        result.put("graphEdgeCount", edges);
        result.put("matterCount", matters);
        result.put("scenarioCounts", scenarios);
        result.put("cases", cases);
        result.put("resolvedCaseIds", cases.stream().map(row ->
                Objects.toString(row.get("caseId"))).toList());
        result.put("caseContexts", caseContexts);
        result.put("supportedRunTypes", SUPPORTED_TYPES);
        return result;
    }

    public Map<String, Object> publicContext(String bankCode, ScopeFilter filter) {
        Map<String, Object> result = new LinkedHashMap<>(context(bankCode, filter));
        result.remove("caseContexts");
        return result;
    }

    public Map<String, Object> payload(String bankCode, ScopeFilter filter, String runType,
                                       Map<String, Object> parameters) {
        String type = Objects.toString(runType, "").trim().toUpperCase();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported task 2/3 run type: " + runType);
        }
        Map<String, Object> scope = context(bankCode, filter);
        List<String> caseIds = castStringList(scope.get("resolvedCaseIds"));
        Map<String, Object> result = parameters == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        if (!"CASCADE_INFERENCE".equals(type)) {
            List<Map<String, Object>> casePayloads = caseIds.stream()
                    .map(caseId -> caseAnalytics.payload(caseId, type, Map.of())).toList();
            mergePayload(type, result, casePayloads);
        }
        result.put("scopeId", scope.get("scopeId"));
        result.put("scopeType", "LOCAL_BANK");
        result.put("bankCode", bankCode);
        result.put("caseIds", caseIds);
        result.put("caseCount", caseIds.size());
        result.put("graphSource", Map.of(
                "scope", "LOCAL_BANK",
                "eventGraph", "CanonicalEvent/1.0",
                "matterGraph", "CoreChain/1.8",
                "caseGraph", "CaseGraphEnvelope/1.0"));
        return result;
    }

    private void mergePayload(String type, Map<String, Object> target,
                              List<Map<String, Object>> payloads) {
        if (EVENT_TYPES.contains(type)) {
            target.put("events", mergeList(payloads, "events", "eventId"));
        }
        switch (type) {
            case "EVENT_CHAIN" -> {
                target.putIfAbsent("minConfidence", 0.35);
                target.putIfAbsent("minSupport", 0.0);
                target.putIfAbsent("maxGapSeconds", 30 * 24 * 3600);
                Map<String, Object> themes = new LinkedHashMap<>();
                payloads.forEach(payload -> themes.putAll(castMap(payload.get("themeRules"))));
                target.put("themeRules", themes);
            }
            case "BEHAVIOR_MATRIX" -> {
                target.putIfAbsent("minConfidence", 0.35);
                target.putIfAbsent("windowSeconds", 7 * 24 * 3600);
                target.putIfAbsent("peakWindowSeconds", 24 * 3600);
            }
            case "RISK_DIFFUSION" -> {
                target.put("nodes", mergeList(payloads, "nodes", "id"));
                target.put("edges", mergeList(payloads, "edges",
                        value -> key(value, "source") + "|" + key(value, "relationType")
                                + "|" + key(value, "target")));
                target.putIfAbsent("damping", 0.85);
                target.putIfAbsent("maxHops", 4);
                target.putIfAbsent("decisionThreshold", 0.5);
            }
            case "INCREMENTAL_LEARNING" -> {
                target.put("observedChains", mergeList(payloads, "observedChains", "chainId"));
                target.put("existingTemplates", mergeList(payloads, "existingTemplates", "templateId"));
                target.putIfAbsent("similarityThreshold", 0.8);
                target.putIfAbsent("supportDecay", 0.95);
            }
            case "META_PATH_DETECTION" -> {
                target.putIfAbsent("minHops", 2);
                target.putIfAbsent("maxHops", 5);
                target.putIfAbsent("deviationThreshold", 0.5);
                target.put("compliantTemplates",
                        mergeList(payloads, "compliantTemplates", "templateId"));
            }
            case "TEMPORAL_ANOMALY" -> {
                target.putIfAbsent("windowSeconds", 3600);
                target.putIfAbsent("burstMinEvents", 3);
                target.putIfAbsent("decisionThreshold", 0.55);
            }
            case "LOCAL_HYPERGRAPH" -> {
                target.put("hyperedges", mergeList(payloads, "hyperedges", "hyperedgeId"));
                target.putIfAbsent("decisionThreshold", 0.5);
            }
            default -> { }
        }
        if (ENVELOPE_TYPES.contains(type)) {
            target.put("caseGraphEnvelope", mergeEnvelopes(payloads));
        }
    }

    private Map<String, Object> mergeEnvelopes(List<Map<String, Object>> payloads) {
        ObjectNode merged = mapper.createObjectNode();
        merged.put("contractVersion", "CaseGraphEnvelope/1.0");
        merged.put("schemaVersion", "CaseGraphEnvelope/1.0");
        ObjectNode tenant = merged.putObject("tenant");
        String bankCode = payloads.stream().map(payload -> mapper.valueToTree(
                        payload.get("caseGraphEnvelope")).path("tenant").path("bankCode").asText())
                .filter(value -> !value.isBlank()).findFirst().orElse("");
        tenant.put("bankCode", bankCode);
        ArrayNode sourceRefs = merged.putArray("sourceRefs");
        List<String> memberCaseIds = new ArrayList<>();
        for (Map<String, Object> payload : payloads) {
            JsonNode source = mapper.valueToTree(payload.get("caseGraphEnvelope"));
            String caseId = source.path("case").path("caseId").asText();
            if (caseId.isBlank()) continue;
            memberCaseIds.add(caseId);
            ObjectNode ref = sourceRefs.addObject();
            ref.put("sourceType", "CASE_GRAPH_ENVELOPE");
            ref.put("sourceRef", caseId);
        }
        memberCaseIds.sort(String::compareTo);
        String envelopeId;
        try {
            String members = mapper.writeValueAsString(memberCaseIds);
            envelopeId = "BANK-SCOPE-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(members.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to identify bank graph envelope", ex);
        }
        merged.put("envelopeId", envelopeId);
        ObjectNode scopeCase = merged.putObject("case");
        scopeCase.put("caseId", envelopeId);
        scopeCase.put("caseVersion", 1);
        merged.set("entities", mergeEnvelopeArray(payloads, "entities", "entityUid"));
        merged.set("events", mergeEnvelopeArray(payloads, "events", "eventId"));
        merged.set("evidences", mergeEnvelopeArray(payloads, "evidences", "evidenceId"));
        merged.set("relationships", mergeEnvelopeArray(payloads, "relationships",
                value -> value.path("type").asText() + "|" + value.path("source").asText()
                        + "|" + value.path("target").asText()));
        ObjectNode producer = merged.putObject("producer");
        producer.put("type", "ADAPTER");
        producer.put("id", "TASK23_LOCAL_BANK_SCOPE_ADAPTER");
        producer.put("version", "1.0");
        merged.put("generatedAt", OffsetDateTime.now().toString());
        merged.put("contentHash", contracts.semanticHash(merged));
        return mapper.convertValue(merged, Map.class);
    }

    private ArrayNode mergeEnvelopeArray(List<Map<String, Object>> payloads, String field,
                                         String idField) {
        return mergeEnvelopeArray(payloads, field, value -> value.path(idField).asText());
    }

    private ArrayNode mergeEnvelopeArray(List<Map<String, Object>> payloads, String field,
                                         java.util.function.Function<JsonNode, String> keyFn) {
        ArrayNode result = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> payload : payloads) {
            JsonNode envelope = mapper.valueToTree(payload.get("caseGraphEnvelope"));
            for (JsonNode value : envelope.path(field)) {
                String key = keyFn.apply(value);
                if ((key.isBlank() ? seen.add(value.toString()) : seen.add(key))) result.add(value);
            }
        }
        return result;
    }

    private List<Map<String, Object>> resolveCases(String bankCode, ScopeFilter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT case_id AS "caseId",case_name AS "caseName",
                       scenario_code AS "scenarioCode",created_at AS "createdAt"
                FROM cf_risk_case WHERE deleted=false AND bank_code=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(bankCode);
        if (filter.scenarioCode() != null) {
            sql.append(" AND scenario_code=?");
            args.add(filter.scenarioCode());
        }
        if (filter.startTime() != null) {
            sql.append(" AND created_at>=?");
            args.add(OffsetDateTime.parse(filter.startTime()));
        }
        if (filter.endTime() != null) {
            sql.append(" AND created_at<=?");
            args.add(OffsetDateTime.parse(filter.endTime()));
        }
        if (!filter.caseIds().isEmpty()) {
            sql.append(" AND case_id IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(filter.caseIds().size(), "?")));
            sql.append(")");
            args.addAll(filter.caseIds());
        }
        sql.append(" ORDER BY created_at DESC,case_id LIMIT ?");
        args.add(filter.limit());
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private ScopeFilter normalize(ScopeFilter value) {
        ScopeFilter source = value == null
                ? new ScopeFilter(null, null, null, List.of(), 200) : value;
        String scenario = blankToNull(source.scenarioCode());
        String start = blankToNull(source.startTime());
        String end = blankToNull(source.endTime());
        if (start != null) OffsetDateTime.parse(start);
        if (end != null) OffsetDateTime.parse(end);
        if (start != null && end != null
                && OffsetDateTime.parse(start).isAfter(OffsetDateTime.parse(end))) {
            throw new IllegalArgumentException("startTime must not be after endTime");
        }
        List<String> ids = source.caseIds() == null ? List.of() : source.caseIds().stream()
                .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank())
                .distinct().sorted().toList();
        int limit = Math.min(Math.max(source.limit() == null ? 200 : source.limit(), 1), 1000);
        return new ScopeFilter(scenario, start, end, ids, limit);
    }

    private String scopeId(String bankCode, ScopeFilter filter, List<Map<String, Object>> cases) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("bankCode", bankCode);
        identity.put("scenarioCode", filter.scenarioCode());
        identity.put("startTime", filter.startTime());
        identity.put("endTime", filter.endTime());
        identity.put("caseIds", cases.stream().map(row ->
                Objects.toString(row.get("caseId"))).sorted().toList());
        try {
            String json = mapper.writeValueAsString(identity);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            return "BANK-SCOPE-" + hash.substring(0, 24);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to identify analytics scope", ex);
        }
    }

    private List<Map<String, Object>> mergeList(List<Map<String, Object>> payloads,
                                                String field, String idField) {
        return mergeList(payloads, field, value -> key(value, idField));
    }

    private List<Map<String, Object>> mergeList(List<Map<String, Object>> payloads,
                                                String field,
                                                java.util.function.Function<Map<String, Object>, String> keyFn) {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        for (Map<String, Object> payload : payloads) {
            Object raw = payload.get(field);
            if (!(raw instanceof List<?> list)) continue;
            for (Object item : list) {
                Map<String, Object> value = mapper.convertValue(item, Map.class);
                String key = keyFn.apply(value);
                values.putIfAbsent(key.isBlank() ? value.toString() : key, value);
            }
        }
        return new ArrayList<>(values.values());
    }

    private Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?>)) return Map.of();
        return mapper.convertValue(value, Map.class);
    }

    private List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Objects::toString).toList();
    }

    private String key(Map<String, Object> value, String field) {
        return Objects.toString(value.get(field), "");
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ScopeFilter(String scenarioCode, String startTime, String endTime,
                              List<String> caseIds, Integer limit) {}
}
