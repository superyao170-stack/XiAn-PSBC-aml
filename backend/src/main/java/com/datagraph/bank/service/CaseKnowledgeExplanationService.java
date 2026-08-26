package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Service
public class CaseKnowledgeExplanationService {
    private static final List<FactGroupDefinition> FACT_GROUPS = List.of(
            new FactGroupDefinition("STRUCTURING_FACT_GROUP", "阈值及测试支付",
                    "存在阈值下拆分及测试支付等资金操作",
                    Set.of("THRESHOLD_STRUCTURING", "THRESHOLD_AMOUNT_ACTIVITY",
                            "ROUND_AMOUNT_STRUCTURING")),
            new FactGroupDefinition("TRANSFER_FACT_GROUP", "多账户资金转移",
                    "资金经多账户或跨区域流转，并涉及账户代持",
                    Set.of("CROSS_BORDER_MULTI_ACCOUNT_TRANSFER", "MULTI_ACCOUNT_TRANSFER",
                            "ACCOUNT_MULE_USAGE", "HIGH_FREQUENCY_PASS_THROUGH",
                            "MULTI_ACCOUNT_LAYERING")),
            new FactGroupDefinition("CASH_FACT_GROUP", "现金资金路径",
                    "涉案资金存在现金存入、取现或现金交付环节",
                    Set.of("CASH_DEPOSIT", "CASH_WITHDRAWAL", "CASH_DELIVERY")),
            new FactGroupDefinition("ASSET_ROUTE_FACT_GROUP", "资产转换路径",
                    "资金经黄金、虚拟资产或链上渠道转换转移",
                    Set.of("PRECIOUS_METAL_CONVERSION", "DIGITAL_ASSET_CONVERSION",
                            "DEFI_ACTIVITY", "CROSS_CHAIN_TRANSFER")),
            new FactGroupDefinition("ORGANIZATION_FACT_GROUP", "组织与操作规避",
                    "存在人员分工、账户安排及操作规避行为",
                    Set.of("ORGANIZED_ROLE_ARRANGEMENT", "OPERATIONAL_EVASION")),
            new FactGroupDefinition("JUSTIFICATION_FACT_GROUP", "资金名义解释",
                    "资金流转使用贷款、服务费或交易等名义解释",
                    Set.of("FICTITIOUS_TRADE", "SERVICE_FEE_JUSTIFICATION",
                            "LOAN_JUSTIFICATION", "LEGITIMATE_ASSET_INVESTMENT"))
    );

    private final CaseCoreChainService coreChainService;
    private final CaseMatterExplanationService matterService;
    private final ObjectMapper mapper;

    public CaseKnowledgeExplanationService(CaseCoreChainService coreChainService,
                                           CaseMatterExplanationService matterService,
                                           ObjectMapper mapper) {
        this.coreChainService = coreChainService;
        this.matterService = matterService;
        this.mapper = mapper;
    }

    public Map<String, Object> explanation(String caseId) {
        Map<String, Object> coreChain = coreChainService.graph(caseId);
        Map<String, Object> reasoning = matterService.reasoning(caseId);
        List<Map<String, Object>> techniques = matterService.techniques(caseId);
        List<Map<String, Object>> matters =
                normalizeMatterSummaries(matterService.matters(caseId));
        List<Map<String, Object>> nodes = new ArrayList<>(mapList(coreChain.get("nodes")));
        List<Map<String, Object>> edges = new ArrayList<>(mapList(coreChain.get("edges")));
        List<Map<String, Object>> knowledgeRefs = mapList(coreChain.get("knowledgeRefs"));
        List<Map<String, Object>> indicators = mapList(reasoning.get("indicatorResults"));
        List<Map<String, Object>> patterns = mapList(reasoning.get("behaviorPatternOccurrences"));
        List<Map<String, Object>> risks = mapList(reasoning.get("riskEvents"));
        augmentExplanationGraph(caseId, nodes, edges, indicators);
        coreChainService.completeBusinessIds(caseId, nodes, edges);

        Map<String, Map<String, Object>> nodeById = index(nodes,
                item -> text(first(item, "uid", "id")));
        Map<String, Map<String, Object>> indicatorById = index(indicators,
                item -> text(item.get("calculationId")));
        Map<String, Map<String, Object>> patternById = index(patterns,
                item -> text(item.get("occurrenceId")));
        Map<String, Map<String, Object>> riskById = index(risks,
                item -> text(item.get("riskEventId")));

        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> ref : knowledgeRefs) {
            if (!"TECHNIQUE".equals(text(ref.get("definitionType")))) continue;
            List<Map<String, Object>> matchingTechniques = techniques.stream()
                    .filter(item -> text(item.get("techniqueCode")).equals(text(ref.get("code"))))
                    .toList();
            Map<String, Object> occurrence = matchingTechniques.stream()
                    .filter(item -> normalizedVersion(item.get("techniqueVersion"))
                            .equals(normalizedVersion(ref.get("version"))))
                    .findFirst().orElse(matchingTechniques.stream().findFirst().orElse(Map.of()));
            if (occurrence.isEmpty()) continue;

            List<Map<String, Object>> linkedIndicators =
                    resolve(stringList(occurrence.get("indicatorResultRefs")), indicatorById);
            List<Map<String, Object>> linkedPatterns =
                    resolve(stringList(occurrence.get("behaviorOccurrenceRefs")), patternById);
            List<Map<String, Object>> linkedRisks =
                    resolve(stringList(occurrence.get("riskEventRefs")), riskById);
            List<String> eventNames = uniqueNames(
                    stringList(occurrence.get("eventRefs")),
                    id -> nodeName(resolveNode(nodeById, caseId, id), "关联事件"));
            List<String> indicatorNames = uniqueNames(linkedIndicators,
                    item -> clean(text(first(item, "indicatorName", "indicatorCode"))));
            List<String> patternNames = uniqueNames(linkedPatterns,
                    item -> {
                        Map<String, Object> node =
                                resolveNode(nodeById, caseId, text(item.get("occurrenceId")));
                        return nodeName(node, text(first(item, "patternName", "patternCode")));
                    });
            List<String> riskNames = uniqueNames(linkedRisks,
                    item -> {
                        Map<String, Object> node =
                                resolveNode(nodeById, caseId, text(item.get("riskEventId")));
                        return nodeName(node, text(first(item, "title", "summary")));
                    });
            List<String> tacticNames = uniqueNames(mapList(ref.get("tactics")),
                    item -> text(first(item, "name", "code")));
            String ownerName = nodeName(
                    resolveNode(nodeById, caseId, text(ref.get("ownerId"))),
                    ownerFallback(text(ref.get("ownerType"))));
            String indicator = joinOr(indicatorNames, "尚无指标结果");
            String pattern = joinOr(patternNames, "尚未关联传统行为模式");
            String risk = joinOr(riskNames, ownerName);
            String tactic = joinOr(tacticNames, "战术待配置");
            String technique = (text(ref.get("code")) + " "
                    + textOr(ref.get("definitionName"), "技术候选")).trim();
            String indicatorMode = indicatorMode(linkedIndicators);
            boolean complete = !eventNames.isEmpty() && !linkedIndicators.isEmpty()
                    && !text(ref.get("code")).isBlank() && !tacticNames.isEmpty();

            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("key", textOr(ref.get("refId"), technique));
            chain.put("fact", eventSummary(eventNames));
            chain.put("eventRefs", stringList(occurrence.get("eventRefs")));
            chain.put("evidenceCount", indicatorEvidenceCount(linkedIndicators));
            chain.put("indicator", indicator);
            chain.put("indicatorMode", indicatorMode);
            chain.put("pattern", pattern);
            chain.put("risk", risk);
            chain.put("technique", technique);
            chain.put("tactic", tactic);
            chain.put("relation", linkedPatterns.isEmpty() && linkedRisks.isEmpty()
                    ? "暂未关联" : "补强解释");
            String conclusion = indicator + "通过指标—技术知识关系映射为 AMLTRIX "
                    + technique + "，归入“" + tactic + "”战术";
            if (!linkedPatterns.isEmpty()) {
                conclusion += "；关联传统行为模式“" + pattern + "”";
            }
            if (!linkedRisks.isEmpty()) {
                conclusion += "，补强风险假设“" + risk + "”";
            }
            chain.put("conclusion", conclusion + "。");
            chain.put("chainType", "TACTIC");
            chain.put("complete", complete);
            chains.add(chain);
        }
        List<Map<String, Object>> baseFactChains = baseFactChains(
                caseId, matters, nodeById, indicatorById, patternById, riskById);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("matters", matters);
        result.put("matterCount", matters.size());
        result.put("convergedMatters", convergedMatters(matters));
        result.put("baseFactChains", baseFactChains);
        result.put("chains", chains);
        Map<String, Object> multiStageMatter = multiStageMatter(chains);
        result.put("multiStageMatter", multiStageMatter);
        result.put("amltrixSupplement",
                amltrixSupplement(matters, chains, multiStageMatter));
        result.put("source", "CASE_CORE_CHAIN");
        return result;
    }

    private void augmentExplanationGraph(
            String caseId,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> indicators) {
        Set<String> nodeIds = nodes.stream().map(this::graphNodeId)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> edgeKeys = edges.stream()
                .map(edge -> text(edge.get("source")) + "|" + text(edge.get("type"))
                        + "|" + text(edge.get("target")))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (Map<String, Object> indicator : indicators) {
            String calculationId = text(indicator.get("calculationId"));
            if (calculationId.isBlank()) continue;
            String indicatorNodeId = scoped(caseId, calculationId);
            Map<String, Object> explanation = indicator.get("explanation") instanceof Map<?, ?> map
                    ? castMap(map) : Map.of();
            List<String> supportingEventNodes = stringList(
                    explanation.get("eventRefs")).stream()
                    .map(ref -> existingNodeId(nodeIds, caseId, ref))
                    .filter(ref -> !ref.isBlank()).distinct().toList();
            // A calculated indicator without a traceable event is not a graph
            // vertex. It remains available in the reasoning table for repair,
            // but must not appear as an orphan node on the matter graph.
            if (supportingEventNodes.isEmpty()
                    && !nodeIds.contains(indicatorNodeId)) continue;
            addPresentationNode(nodes, nodeIds, indicatorNodeId, "INDICATORRESULT",
                    "INDICATOR_RESULT",
                    textOr(first(indicator, "indicatorName", "indicatorCode"), "指标结果"),
                    indicator, textOr(indicator.get("status"), "CALCULATED"));
            for (String eventNodeId : supportingEventNodes) {
                addPresentationEdge(edges, edgeKeys, eventNodeId, indicatorNodeId,
                        "EVENT_SUPPORTS_INDICATOR", "事件形成指标结果",
                        "EVENT", "INDICATOR_RESULT");
            }
        }
    }

    private void addPresentationNode(
            List<Map<String, Object>> nodes,
            Set<String> nodeIds,
            String id,
            String label,
            String canonicalType,
            String name,
            Map<String, Object> sourceProperties,
            String epistemicType) {
        if (!nodeIds.add(id)) return;
        Map<String, Object> properties = new LinkedHashMap<>(sourceProperties);
        properties.put("canonicalType", canonicalType);
        properties.put("nodeType", canonicalType);
        properties.put("graphPlane", "REASONING_GRAPH");
        properties.put("graphDomain", "CASE_EXPLANATION");
        properties.put("objectSemantics", "INSTANCE");
        properties.put("epistemicType", epistemicType);
        properties.put("caseId", id.substring(0, id.indexOf("::")));
        // The calculation/occurrence ID is an internal identifier. A stable,
        // case-scoped business ID is assigned after the complete explanation
        // graph (including presentation edges) has been assembled.
        properties.remove("businessId");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("uid", id);
        node.put("label", label);
        node.put("type", canonicalType);
        node.put("name", name);
        node.put("properties", properties);
        nodes.add(node);
    }

    private void addPresentationEdge(
            List<Map<String, Object>> edges,
            Set<String> edgeKeys,
            String source,
            String target,
            String type,
            String displayName,
            String sourceType,
            String targetType) {
        String key = source + "|" + type + "|" + target;
        if (!edgeKeys.add(key)) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("relationName", displayName);
        properties.put("displayName", displayName);
        properties.put("sourceType", sourceType);
        properties.put("targetType", targetType);
        properties.put("edgeCategory", "INFERENCE");
        properties.put("edgeOrigin", "CASE_EXPLANATION");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "DISPLAY-" + Integer.toUnsignedString(key.hashCode(), 16));
        edge.put("source", source);
        edge.put("target", target);
        edge.put("type", type);
        edge.put("properties", properties);
        edges.add(edge);
    }

    private String scoped(String caseId, String id) {
        return id.startsWith(caseId + "::") ? id : caseId + "::" + id;
    }

    private String existingNodeId(Set<String> nodeIds, String caseId, String id) {
        if (nodeIds.contains(id)) return id;
        String scoped = scoped(caseId, id);
        return nodeIds.contains(scoped) ? scoped : "";
    }

    /**
     * The case detail keeps the complete explanation graph. A panorama combines
     * several cases in one canvas, so it receives a bounded, case-centred
     * projection that prioritizes events and reasoning evidence.
     */
    public Map<String, Object> panoramaExplanation(String caseId, int requestedLimit) {
        Map<String, Object> full = explanation(caseId);
        List<Map<String, Object>> allNodes = mapList(full.get("nodes"));
        List<Map<String, Object>> allEdges = mapList(full.get("edges"));
        int limit = Math.min(Math.max(requestedLimit, 50), 500);
        if (allNodes.size() <= limit) {
            full.put("projection", "PANORAMA");
            full.put("originalNodeCount", allNodes.size());
            full.put("originalEdgeCount", allEdges.size());
            full.put("truncated", false);
            return full;
        }
        List<Map<String, Object>> ranked = new ArrayList<>(allNodes);
        ranked.sort(java.util.Comparator.comparingInt(this::panoramaPriority));
        List<Map<String, Object>> nodes = new ArrayList<>(ranked.subList(0, limit));
        Set<String> nodeIds = nodes.stream().map(this::graphNodeId)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> edges = allEdges.stream()
                .filter(edge -> nodeIds.contains(text(edge.get("source")))
                        && nodeIds.contains(text(edge.get("target"))))
                .limit(limit * 4L)
                .toList();
        full.put("nodes", nodes);
        full.put("edges", edges);
        full.put("projection", "PANORAMA");
        full.put("originalNodeCount", allNodes.size());
        full.put("originalEdgeCount", allEdges.size());
        full.put("truncated", true);
        return full;
    }

    private int panoramaPriority(Map<String, Object> node) {
        Map<String, Object> properties = node.get("properties") instanceof Map<?, ?> value
                ? castMap(value) : Map.of();
        String type = textOr(first(node, "label", "type"),
                text(first(properties, "nodeType", "type"))).replaceAll("[^A-Za-z]", "")
                .toUpperCase();
        if ("CASE".equals(type)) return 0;
        if ("EVENT".equals(type) || "CANONICALEVENT".equals(type)) return 1;
        if (Set.of("MATTER", "ALTERNATIVEEXPLANATION", "INVESTIGATIONHYPOTHESIS")
                .contains(type)) return 2;
        if (Set.of("INDICATORRESULT", "BEHAVIORPATTERN", "BEHAVIORPATTERNOCCURRENCE",
                "RISKHYPOTHESIS", "RISKEVENT", "TECHNIQUE", "TECHNIQUEOCCURRENCE",
                "TACTIC").contains(type)) return 3;
        if ("EVIDENCE".equals(type)) return 4;
        if (Set.of("CUSTOMER", "ORGANIZATION", "MERCHANT", "ACCOUNT").contains(type)) return 5;
        return 6;
    }

    private String graphNodeId(Map<String, Object> node) {
        return text(first(node, "id", "uid"));
    }

    private List<Map<String, Object>> baseFactChains(
            String caseId,
            List<Map<String, Object>> matters,
            Map<String, Map<String, Object>> nodeById,
            Map<String, Map<String, Object>> indicatorById,
            Map<String, Map<String, Object>> patternById,
            Map<String, Map<String, Object>> riskById) {
        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> matter : matters) {
            List<String> eventNames = uniqueNames(stringList(matter.get("eventRefs")),
                    id -> nodeName(resolveNode(nodeById, caseId, id), "关联事件"));
            List<String> indicatorNames = uniqueNames(
                    resolve(stringList(matter.get("indicatorResultRefs")), indicatorById),
                    item -> clean(text(first(item, "indicatorName", "indicatorCode"))));
            List<String> patternNames = uniqueNames(
                    resolve(stringList(matter.get("behaviorOccurrenceRefs")), patternById),
                    item -> clean(text(first(item, "patternName", "patternCode"))));
            List<String> riskNames = uniqueNames(
                    resolve(stringList(matter.get("riskEventRefs")), riskById),
                    item -> clean(text(first(item, "title", "summary"))));
            String summary = textOr(matter.get("summary"), "基础事理结论");
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("key", textOr(matter.get("matterId"), summary));
            chain.put("fact", eventNames.isEmpty()
                    ? "当前案例事件及来源证据"
                    : String.join("、", eventNames) + "及其来源证据");
            chain.put("indicator", joinOr(indicatorNames, "尚无正式指标命中"));
            chain.put("pattern", joinOr(patternNames, "尚未形成行为模式"));
            chain.put("risk", joinOr(riskNames, "尚未形成风险假设"));
            chain.put("technique", "尚未映射 AMLTRIX 技术");
            chain.put("tactic", "尚未归入 AMLTRIX 战术");
            chain.put("conclusion", summary);
            chain.put("limit", "该链是有事件、原始记录和正式指标引用的基础事理归纳；"
                    + "不等同于已经形成模式、风险、技术和战术映射的完整战术链。");
            chain.put("chainType", "BASE_FACT");
            chain.put("complete", false);
            chains.add(chain);
        }
        return chains;
    }

    private Map<String, Object> multiStageMatter(List<Map<String, Object>> chains) {
        List<Map<String, Object>> complete = chains.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("complete"))
                        || (!item.containsKey("complete")
                        && !"尚未解析战术".equals(item.get("tactic"))))
                .toList();
        if (complete.isEmpty()) return null;
        Set<String> tactics = new LinkedHashSet<>();
        List<String> conclusions = new ArrayList<>();
        for (Map<String, Object> item : complete) {
            tactics.add(text(item.get("tactic")));
            conclusions.add(text(item.get("conclusion")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stageCount", complete.size());
        result.put("tactics", List.copyOf(tactics));
        result.put("conclusions", conclusions);
        result.put("chainLabel", "条完整战术链");
        result.put("itemLabel", "战术链");
        result.put("sourceType", "TACTIC");
        result.put("sourceDescription",
                "来源：事件与证据形成指标结果后，经指标—技术和技术—战术知识关系映射形成。");
        result.put("summary", "本案由 " + complete.size() + " 条完整战术链共同解释，涉及 "
                + String.join("、", tactics) + "。");
        return result;
    }

    private List<Map<String, Object>> normalizeMatterSummaries(
            List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : source) {
            Map<String, Object> normalized = new LinkedHashMap<>(item);
            normalized.put("summary", cleanMatterSummary(text(item.get("summary"))));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> convergedMatters(
            List<Map<String, Object>> source) {
        Set<String> groupedIds = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (FactGroupDefinition definition : FACT_GROUPS) {
            List<Map<String, Object>> members = source.stream()
                    .filter(item -> definition.types().contains(text(item.get("matterType"))))
                    .toList();
            if (members.size() < 2) continue;
            members.forEach(item -> groupedIds.add(text(item.get("matterId"))));
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("matterId", "GROUP-" + definition.code());
            group.put("matterType", definition.code());
            group.put("matterTypeName", definition.name());
            group.put("summary", definition.summary());
            group.put("certainty", "DERIVED");
            for (String field : List.of("eventRefs", "evidenceRefs", "sourceRefs",
                    "indicatorResultRefs", "techniqueOccurrenceRefs",
                    "behaviorOccurrenceRefs", "riskEventRefs", "techniqueCodes")) {
                group.put(field, mergeListField(members, field));
            }
            group.put("memberMatterIds", members.stream()
                    .map(item -> text(item.get("matterId"))).filter(id -> !id.isBlank()).toList());
            group.put("memberMatters", members);
            result.add(group);
        }
        source.stream()
                .filter(item -> !groupedIds.contains(text(item.get("matterId"))))
                .forEach(result::add);
        result.sort((left, right) -> Integer.compare(
                matterSupportScore(right), matterSupportScore(left)));
        return result;
    }

    private List<Object> mergeListField(List<Map<String, Object>> members, String field) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> member : members) {
            JsonNode node = jsonNode(member.get(field));
            if (!node.isArray()) continue;
            node.forEach(item -> {
                Object value = mapper.convertValue(item, Object.class);
                values.putIfAbsent(item.toString(), value);
            });
        }
        return List.copyOf(values.values());
    }

    private int matterSupportScore(Map<String, Object> item) {
        return stringList(item.get("indicatorResultRefs")).size() * 100
                + stringList(item.get("eventRefs")).size() * 10
                + jsonNode(item.get("evidenceRefs")).size();
    }

    private String cleanMatterSummary(String value) {
        return clean(value
                .replaceAll("材料(?:记载|反映)(?:的)?", "")
                .replaceAll("[；;]\\s*[；;]+", "；")
                .replaceAll("^\\s*[；;，,、]+", ""));
    }

    private String eventSummary(List<String> eventNames) {
        if (eventNames.isEmpty()) return "事件与证据待关联";
        int visible = Math.min(eventNames.size(), 3);
        String names = String.join("、", eventNames.subList(0, visible));
        return eventNames.size() > visible
                ? names + "等" + eventNames.size() + "个关联事件"
                : names;
    }

    private String indicatorMode(List<Map<String, Object>> indicators) {
        Set<String> modes = new LinkedHashSet<>();
        for (Map<String, Object> indicator : indicators) {
            String mode = text(first(indicator, "assertionMode"));
            if (mode.isBlank() && indicator.get("explanation") instanceof Map<?, ?> explanation) {
                mode = text(explanation.get("assertionMode"));
            }
            if (mode.isBlank() && indicator.containsKey("semanticScore")) {
                mode = "SEMANTIC_MATCH";
            }
            if (!mode.isBlank()) modes.add(mode);
        }
        if (modes.isEmpty()) return "UNKNOWN";
        return modes.size() == 1 ? modes.iterator().next() : "MIXED";
    }

    private int indicatorEvidenceCount(List<Map<String, Object>> indicators) {
        Set<String> evidence = new LinkedHashSet<>();
        for (Map<String, Object> indicator : indicators) {
            if (!(indicator.get("explanation") instanceof Map<?, ?> explanation)) continue;
            JsonNode refs = jsonNode(explanation.get("evidenceRefs"));
            if (refs.isArray()) refs.forEach(item -> evidence.add(item.toString()));
        }
        return evidence.size();
    }

    private Map<String, Object> amltrixSupplement(
            List<Map<String, Object>> traditionalFacts,
            List<Map<String, Object>> amltrixChains,
            Map<String, Object> multiStageMatter) {
        boolean hasTraditionalFacts = !traditionalFacts.isEmpty();
        boolean hasAmltrix = !amltrixChains.isEmpty();
        String status = hasAmltrix
                ? hasTraditionalFacts ? "SUPPLEMENTED" : "AMLTRIX_ONLY"
                : "NO_AMLTRIX_HIT";
        String summary;
        if (hasTraditionalFacts && hasAmltrix) {
            summary = "AMLTRIX命中" + amltrixChains.size()
                    + "条技术—战术解释链，作为传统事实归纳的补充解释。";
        } else if (hasAmltrix) {
            summary = "AMLTRIX已形成" + amltrixChains.size()
                    + "条技术—战术解释链；传统事实归纳需继续核验。";
        } else {
            summary = "当前没有满足门槛的AMLTRIX技术—战术解释链。";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("summary", summary);
        result.put("traditionalFactCount", traditionalFacts.size());
        result.put("amltrixChainCount", amltrixChains.size());
        result.put("supplemented", hasTraditionalFacts && hasAmltrix);
        result.put("multiStageMatter", multiStageMatter);
        result.put("boundary", "AMLTRIX命中可以补充传统事实归纳，但不能反向改写事实。");
        return result;
    }

    private Map<String, Object> resolveNode(Map<String, Map<String, Object>> nodes,
                                            String caseId, String id) {
        if (id.isBlank()) return Map.of();
        Map<String, Object> node = nodes.get(id);
        if (node != null) return node;
        node = nodes.get(caseId + "::" + id);
        if (node != null) return node;
        String prefix = caseId + "::";
        return id.startsWith(prefix) ? nodes.getOrDefault(id.substring(prefix.length()), Map.of())
                : Map.of();
    }

    private String nodeName(Map<String, Object> node, String fallback) {
        if (node == null || node.isEmpty()) return textOr(fallback, "相关业务节点");
        Object properties = node.get("properties");
        Map<String, Object> props = properties instanceof Map<?, ?> map
                ? castMap(map) : Map.of();
        return textOr(first(node, "name"),
                textOr(first(props, "displayName", "eventName", "event_name", "name",
                        "indicatorName", "patternName", "title", "summary"), fallback));
    }

    private String ownerFallback(String ownerType) {
        return switch (ownerType) {
            case "BEHAVIOR_PATTERN" -> "当前行为模式";
            case "RISK_HYPOTHESIS" -> "当前风险判断";
            case "INDICATOR_RESULT" -> "当前指标结果";
            default -> "当前案例判断";
        };
    }

    private <T> List<String> uniqueNames(List<T> values, Function<T, String> mapper) {
        Set<String> names = new LinkedHashSet<>();
        for (T value : values) {
            String name = clean(mapper.apply(value));
            if (!name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    private List<Map<String, Object>> resolve(List<String> ids,
                                              Map<String, Map<String, Object>> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> value = values.get(id);
            if (value != null) result.add(value);
        }
        return result;
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> values,
                                                   Function<Map<String, Object>, String> key) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String id = key.apply(value);
            if (!id.isBlank()) result.put(id, value);
        }
        return result;
    }

    private List<String> stringList(Object value) {
        JsonNode node = jsonNode(value);
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String current = item.asText();
            if (!current.isBlank()) result.add(current);
        });
        return result;
    }

    private List<Map<String, Object>> mapList(Object value) {
        JsonNode node = jsonNode(value);
        if (!node.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        node.forEach(item -> result.add(mapper.convertValue(item, Map.class)));
        return result;
    }

    private JsonNode jsonNode(Object value) {
        if (value instanceof JsonNode node) return node;
        try {
            return mapper.valueToTree(value == null ? List.of() : value);
        } catch (Exception ex) {
            return mapper.createArrayNode();
        }
    }

    private Map<String, Object> castMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(text(key), item));
        return result;
    }

    private Object first(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            Object item = value.get(key);
            if (item != null && !text(item).isBlank()) return item;
        }
        return null;
    }

    private String joinOr(List<String> values, String fallback) {
        return values.isEmpty() ? fallback : String.join("、", values);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("[。；;、，,\\s]+$", "");
    }

    private String normalizedVersion(Object value) {
        String raw = text(value).trim();
        try {
            return new BigDecimal(raw).stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? Objects.toString(fallback, "") : result;
    }

    private String text(Object value) {
        return Objects.toString(value, "");
    }

    private record FactGroupDefinition(
            String code, String name, String summary, Set<String> types) {
    }
}
