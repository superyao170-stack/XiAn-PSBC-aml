package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts the current case event graph and matter/reasoning graph to task 2/3
 * analytics contracts. It deliberately contains no second graph model: the
 * current core-chain graph remains the source of nodes, relations and scores.
 */
@Service
public class Task23GraphAnalyticsService {
    private static final Set<String> EVENT_TYPES = Set.of(
            "EVENT_CHAIN", "BEHAVIOR_MATRIX", "TEMPORAL_ANOMALY");
    private static final Set<String> GRAPH_TYPES = Set.of(
            "RISK_DIFFUSION", "META_PATH_DETECTION", "LOCAL_HYPERGRAPH");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "EVENT_CHAIN", "BEHAVIOR_MATRIX", "RISK_DIFFUSION",
            "INCREMENTAL_LEARNING", "META_PATH_DETECTION",
            "TEMPORAL_ANOMALY", "LOCAL_HYPERGRAPH", "CASCADE_INFERENCE");

    private final ContractProjectionService projections;
    private final VersionedContractService contracts;
    private final CaseCoreChainService coreChains;
    private final ObjectMapper mapper;

    public Task23GraphAnalyticsService(ContractProjectionService projections,
                                       VersionedContractService contracts,
                                       CaseCoreChainService coreChains,
                                       ObjectMapper mapper) {
        this.projections = projections;
        this.contracts = contracts;
        this.coreChains = coreChains;
        this.mapper = mapper;
    }

    public String bankCode(String caseId) {
        return coreChains.caseBankCode(caseId);
    }

    public Map<String, Object> context(String caseId) {
        Map<String, Object> projected = projections.captureCase(caseId);
        ObjectNode envelope = ((JsonNode) projected.get("payload")).deepCopy();
        List<ObjectNode> events = canonicalEvents(projected, caseId);
        ArrayNode eventArray = mapper.createArrayNode();
        events.forEach(eventArray::add);
        envelope.set("events", eventArray);

        Map<String, Object> graph = coreChains.graph(caseId);
        enrichEnvelope(envelope, graph);
        envelope.put("schemaVersion", "bankgraph-event-matter-analytics/1.0");
        ObjectNode producer = envelope.putObject("producer");
        producer.put("type", "ADAPTER");
        producer.put("id", "TASK23_CURRENT_GRAPH_ADAPTER");
        producer.put("version", "1.0");
        envelope.put("generatedAt", Instant.now().toString());
        envelope.remove("contentHash");
        envelope.put("contentHash", contracts.semanticHash(envelope));

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        nodes(graph).forEach(node -> typeCounts.merge(canonicalType(node), 1L, Long::sum));
        List<?> matters = graph.get("narrativeProjections") instanceof List<?> values
                ? values : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("bankCode", envelope.path("tenant").path("bankCode").asText());
        result.put("scenarioCode", events.stream().map(event ->
                event.path("scenario").path("code").asText()).filter(value -> !value.isBlank())
                .findFirst().orElse("UNKNOWN"));
        result.put("eventCount", events.size());
        result.put("graphNodeCount", nodes(graph).size());
        result.put("graphEdgeCount", edges(graph).size());
        result.put("matterCount", matters.size());
        result.put("nodeTypeCounts", typeCounts);
        result.put("graphSummary", graph.getOrDefault("summary", Map.of()));
        result.put("supportedRunTypes", SUPPORTED_TYPES);
        result.put("caseGraphEnvelope", envelope);
        return result;
    }

    public Map<String, Object> payload(String caseId, String runType,
                                       Map<String, Object> parameters) {
        String type = Objects.toString(runType, "").trim().toUpperCase();
        if (!SUPPORTED_TYPES.contains(type))
            throw new IllegalArgumentException("Unsupported task 2/3 run type: " + runType);
        Map<String, Object> params = parameters == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        Map<String, Object> context = context(caseId);
        ObjectNode envelope = (ObjectNode) context.get("caseGraphEnvelope");
        List<Map<String, Object>> events = new ArrayList<>();
        envelope.path("events").forEach(event -> events.add(mapper.convertValue(
                event, mapper.getTypeFactory().constructMapType(
                        LinkedHashMap.class, String.class, Object.class))));

        if (EVENT_TYPES.contains(type)) params.put("events", events);
        if (GRAPH_TYPES.contains(type)) params.put("caseGraphEnvelope",
                mapper.convertValue(envelope, Map.class));
        Map<String, Object> graph = coreChains.graph(caseId);
        switch (type) {
            case "EVENT_CHAIN" -> {
                params.putIfAbsent("minConfidence", 0.35);
                params.putIfAbsent("minSupport", 0.0);
                params.putIfAbsent("maxGapSeconds", 30 * 24 * 3600);
                params.putIfAbsent("themeRules", matterThemes(graph));
            }
            case "BEHAVIOR_MATRIX" -> {
                params.putIfAbsent("minConfidence", 0.35);
                params.putIfAbsent("windowSeconds", 7 * 24 * 3600);
                params.putIfAbsent("peakWindowSeconds", 24 * 3600);
            }
            case "RISK_DIFFUSION" -> {
                params.put("nodes", diffusionNodes(graph));
                params.put("edges", diffusionEdges(graph));
                params.putIfAbsent("damping", 0.85);
                params.putIfAbsent("maxHops", 4);
                params.putIfAbsent("decisionThreshold", 0.5);
            }
            case "INCREMENTAL_LEARNING" -> {
                params.put("observedChains", observedChains(events));
                params.put("existingTemplates", graphTemplates(graph));
                params.putIfAbsent("similarityThreshold", 0.8);
                params.putIfAbsent("supportDecay", 0.95);
            }
            case "META_PATH_DETECTION" -> {
                params.putIfAbsent("minHops", 2);
                params.putIfAbsent("maxHops", 5);
                params.putIfAbsent("deviationThreshold", 0.5);
                params.putIfAbsent("compliantTemplates", compliantTemplates(graph));
            }
            case "TEMPORAL_ANOMALY" -> {
                params.putIfAbsent("windowSeconds", 3600);
                params.putIfAbsent("burstMinEvents", 3);
                params.putIfAbsent("decisionThreshold", 0.55);
            }
            case "LOCAL_HYPERGRAPH" -> {
                params.put("hyperedges", matterHyperedges(graph));
                params.putIfAbsent("decisionThreshold", 0.5);
            }
            case "CASCADE_INFERENCE" -> {
                Object ids = params.get("upstreamRunIds");
                if (!(ids instanceof List<?> values) || values.isEmpty())
                    throw new IllegalArgumentException("级联推理至少需要一个已成功的任务3上游运行");
                params.putIfAbsent("minModules", 2);
                params.putIfAbsent("decisionThreshold", 0.65);
            }
            default -> { }
        }
        params.put("caseId", caseId);
        params.put("graphSource", Map.of(
                "eventGraph", "CanonicalEvent/1.0",
                "matterGraph", "CoreChain/" + Objects.toString(
                        ((Map<?, ?>) context.get("graphSummary")).get("schemaVersion"), "1.8"),
                "caseGraph", "CaseGraphEnvelope/1.0"));
        return params;
    }

    private List<ObjectNode> canonicalEvents(Map<String, Object> projected, String caseId) {
        List<ObjectNode> result = new ArrayList<>();
        Object raw = projected.get("canonicalEvents");
        if (raw instanceof List<?> instances) {
            for (Object value : instances) {
                if (!(value instanceof Map<?, ?> instance)) continue;
                JsonNode payload = mapper.valueToTree(instance.get("payload"));
                if (!payload.isObject()) continue;
                ObjectNode event = ((ObjectNode) payload).deepCopy();
                if (event.path("participants").isEmpty()) {
                    ObjectNode participant = event.withArray("participants").addObject();
                    participant.put("entityUid", caseId);
                    participant.put("role", "SUBJECT");
                }
                result.add(event);
            }
        }
        result.sort(Comparator.comparing(event ->
                event.path("occurredAt").path("start").asText("")));
        return result;
    }

    private void enrichEnvelope(ObjectNode envelope, Map<String, Object> graph) {
        ArrayNode entities = envelope.withArray("entities");
        Set<String> ids = new LinkedHashSet<>();
        entities.forEach(entity -> ids.add(entity.path("entityUid").asText(
                entity.path("id").asText())));
        Set<String> eventIds = new LinkedHashSet<>();
        envelope.path("events").forEach(event -> eventIds.add(event.path("eventId").asText()));
        for (Map<String, Object> node : nodes(graph)) {
            String id = text(node.get("id"));
            if (id.isBlank() || eventIds.contains(id)
                    || "CASE".equals(canonicalType(node)) || !ids.add(id)) continue;
            ObjectNode entity = entities.addObject();
            entity.put("entityUid", id);
            entity.put("entityType", canonicalType(node));
            entity.put("name", text(node.get("name")));
            entity.set("properties", mapper.valueToTree(node.getOrDefault("properties", Map.of())));
        }
        ArrayNode relationships = envelope.withArray("relationships");
        Set<String> keys = new LinkedHashSet<>();
        relationships.forEach(edge -> keys.add(edge.path("type").asText() + "|"
                + edge.path("source").asText() + "|" + edge.path("target").asText()));
        for (Map<String, Object> edge : edges(graph)) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            String type = text(edge.get("type"));
            if (source.isBlank() || target.isBlank() || type.isBlank()
                    || !keys.add(type + "|" + source + "|" + target)) continue;
            ObjectNode relation = relationships.addObject();
            relation.put("relationshipId", text(edge.get("id")));
            relation.put("type", type);
            relation.put("source", source);
            relation.put("target", target);
            relation.put("directed", true);
            relation.put("riskWeight", edgeWeight(edge));
            relation.set("properties", mapper.valueToTree(edge.getOrDefault("properties", Map.of())));
        }
    }

    private List<Map<String, Object>> diffusionNodes(Map<String, Object> graph) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : nodes(graph)) {
            String type = canonicalType(node);
            if ("CASE".equals(type)) continue;
            Map<?, ?> props = properties(node);
            double score = firstScore(props, "risk_confidence", "riskConfidence",
                    "pattern_confidence", "patternConfidence", "calibrated_probability",
                    "calibratedProbability", "confidence");
            if ("RISK_HYPOTHESIS".equals(type)) score = Math.max(score, 0.7);
            else if ("BEHAVIOR_PATTERN".equals(type)) score = Math.max(score, 0.55);
            else if ("EVENT".equals(type)) score *= 0.5;
            result.add(Map.of("id", text(node.get("id")), "type", type,
                    "initialRisk", clamp(score)));
        }
        return result;
    }

    private List<Map<String, Object>> diffusionEdges(Map<String, Object> graph) {
        return edges(graph).stream().map(edge -> Map.<String, Object>of(
                "source", text(edge.get("source")),
                "target", text(edge.get("target")),
                "relationType", text(edge.get("type")),
                "weight", edgeWeight(edge),
                "cooccurrence", 1.0,
                "temporalStrength", 1.0)).toList();
    }

    private List<Map<String, Object>> observedChains(List<Map<String, Object>> events) {
        Map<String, List<Map<String, Object>>> bySubject = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            JsonNode node = mapper.valueToTree(event);
            JsonNode participants = node.path("participants");
            String subject = participants.isEmpty() ? "" :
                    participants.get(0).path("entityUid").asText();
            if (!subject.isBlank()) bySubject.computeIfAbsent(subject, ignored ->
                    new ArrayList<>()).add(event);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        bySubject.forEach((subject, values) -> {
            values.sort(Comparator.comparing(value -> mapper.valueToTree(value)
                    .path("occurredAt").path("start").asText()));
            List<String> eventTypes = values.stream().map(value -> {
                JsonNode node = mapper.valueToTree(value);
                return node.path("action").path("code").asText(
                        node.path("standardEventCode").asText());
            }).toList();
            result.add(Map.of("chainId", "CASE-CHAIN-" + subject,
                    "subjectId", subject, "eventTypes", eventTypes));
        });
        return result;
    }

    private List<Map<String, Object>> graphTemplates(Map<String, Object> graph) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : nodes(graph)) {
            if (!"BEHAVIOR_PATTERN".equals(canonicalType(node))) continue;
            String id = text(node.get("id"));
            List<String> sequence = incomingEventTypes(id, graph);
            if (!sequence.isEmpty()) result.add(Map.of(
                    "templateId", id, "eventTypes", sequence,
                    "support", firstScore(properties(node), "pattern_confidence",
                            "patternConfidence", "confidence")));
        }
        return result;
    }

    private List<Map<String, Object>> compliantTemplates(Map<String, Object> graph) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : nodes(graph)) {
            if (!"BEHAVIOR_PATTERN".equals(canonicalType(node))) continue;
            Map<?, ?> props = properties(node);
            if (!"NORMAL".equalsIgnoreCase(text(props.get("pattern_class")))) continue;
            List<String> relationTypes = incomingEdges(text(node.get("id")), graph).stream()
                    .map(edge -> text(edge.get("type"))).toList();
            if (!relationTypes.isEmpty()) result.add(Map.of("templateId", text(node.get("id")),
                    "relationTypes", relationTypes));
        }
        return result;
    }

    private Map<String, List<String>> matterThemes(Map<String, Object> graph) {
        Map<String, List<String>> themes = new LinkedHashMap<>();
        Object raw = graph.get("narrativeProjections");
        if (raw instanceof List<?> matters) {
            for (Object value : matters) {
                if (!(value instanceof Map<?, ?> matter)) continue;
                String theme = text(matter.get("matter_type"));
                List<String> eventTypes = eventTypes(refs(matter.get("event_refs")), graph);
                if (!theme.isBlank() && !eventTypes.isEmpty()) themes.put(theme, eventTypes);
            }
        }
        return themes;
    }

    private List<Map<String, Object>> matterHyperedges(Map<String, Object> graph) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : nodes(graph)) {
            String type = canonicalType(node);
            if (!Set.of("BEHAVIOR_PATTERN", "RISK_HYPOTHESIS", "TECHNIQUE_OCCURRENCE")
                    .contains(type)) continue;
            String id = text(node.get("id"));
            LinkedHashSet<String> members = new LinkedHashSet<>();
            members.add(id);
            incomingEdges(id, graph).forEach(edge -> members.add(text(edge.get("source"))));
            edges(graph).stream().filter(edge -> id.equals(text(edge.get("source"))))
                    .forEach(edge -> members.add(text(edge.get("target"))));
            members.removeIf(String::isBlank);
            if (members.size() < 2) continue;
            double risk = firstScore(properties(node), "risk_confidence", "riskConfidence",
                    "pattern_confidence", "patternConfidence", "confidence");
            result.add(Map.of("hyperedgeId", "HE-" + id, "type", type,
                    "nodeIds", new ArrayList<>(members), "baseRisk", Math.max(0.5, risk),
                    "featureSimilarity", 0.8, "temporalStrength", 1.0));
        }
        Object raw = graph.get("narrativeProjections");
        if (raw instanceof List<?> matters) {
            for (Object value : matters) {
                if (!(value instanceof Map<?, ?> matter)) continue;
                LinkedHashSet<String> members = new LinkedHashSet<>();
                members.addAll(refs(matter.get("event_refs")));
                members.addAll(refs(matter.get("behavior_occurrence_refs")).stream()
                        .map(id -> scoped(text(graph.get("caseId")), id)).toList());
                members.addAll(refs(matter.get("risk_event_refs")).stream()
                        .map(id -> scoped(text(graph.get("caseId")), id)).toList());
                if (members.size() < 2) continue;
                String id = text(matter.get("matter_id"));
                result.add(Map.of("hyperedgeId", "HE-MATTER-" + id,
                        "type", "MATTER_EXPLANATION", "nodeIds", new ArrayList<>(members),
                        "baseRisk", certaintyRisk(text(matter.get("certainty"))),
                        "featureSimilarity", 0.85, "temporalStrength", 1.0));
            }
        }
        return result;
    }

    private List<Map<String, Object>> incomingEdges(String target, Map<String, Object> graph) {
        return edges(graph).stream().filter(edge -> target.equals(text(edge.get("target")))).toList();
    }

    private List<String> incomingEventTypes(String target, Map<String, Object> graph) {
        List<String> ids = incomingEdges(target, graph).stream()
                .map(edge -> text(edge.get("source"))).toList();
        return eventTypes(ids, graph);
    }

    private List<String> eventTypes(List<String> ids, Map<String, Object> graph) {
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            nodes(graph).stream().filter(node -> id.equals(text(node.get("id"))))
                    .findFirst().ifPresent(node -> {
                        if ("EVENT".equals(canonicalType(node)))
                            result.add(firstText(properties(node), "event_frame_code",
                                    "event_type", "standardEventCode", "action"));
                    });
        }
        result.removeIf(String::isBlank);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> graph) {
        return graph.get("nodes") instanceof List<?> values
                ? (List<Map<String, Object>>) values : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> graph) {
        return graph.get("edges") instanceof List<?> values
                ? (List<Map<String, Object>>) values : List.of();
    }

    private Map<?, ?> properties(Map<String, Object> value) {
        return value.get("properties") instanceof Map<?, ?> props ? props : Map.of();
    }

    private String canonicalType(Map<String, Object> node) {
        return firstText(properties(node), "canonicalType", "canonical_type",
                "type", "label");
    }

    private double edgeWeight(Map<String, Object> edge) {
        Map<?, ?> props = properties(edge);
        double value = firstScore(props, "confidence", "riskWeight", "weight");
        if (value <= 0) {
            String category = firstText(props, "edgeCategory", "edge_category");
            value = "INFERENCE".equals(category) ? 0.8 : "EVIDENCE".equals(category) ? 0.7 : 0.55;
        }
        return clamp(value);
    }

    private double firstScore(Map<?, ?> value, String... keys) {
        for (String key : keys) {
            Object raw = value.get(key);
            if (raw instanceof Number number) return number.doubleValue();
            try {
                if (raw != null && !raw.toString().isBlank()) return Double.parseDouble(raw.toString());
            } catch (NumberFormatException ignored) { }
        }
        return 0.0;
    }

    private String firstText(Map<?, ?> value, String... keys) {
        for (String key : keys) {
            String result = text(value.get(key));
            if (!result.isBlank()) return result;
        }
        return "";
    }

    private List<String> refs(Object raw) {
        JsonNode node = mapper.valueToTree(raw == null ? List.of() : raw);
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> {
            if (!value.asText().isBlank()) result.add(value.asText());
        });
        return result;
    }

    private double certaintyRisk(String certainty) {
        return switch (certainty.toUpperCase()) {
            case "CONFIRMED", "HIGH" -> 0.85;
            case "PROBABLE", "MEDIUM" -> 0.7;
            default -> 0.55;
        };
    }

    private String scoped(String caseId, String id) {
        return id.startsWith(caseId + "::") ? id : caseId + "::" + id;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }
}
