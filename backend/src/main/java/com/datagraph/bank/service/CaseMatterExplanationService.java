package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CaseMatterExplanationService {
    private static final Map<String, String> MATTER_TYPE_NAMES = Map.ofEntries(
            Map.entry("CROSS_SUBJECT_TRANSFER", "跨主体转移"),
            Map.entry("ASSET_RECONFIGURATION", "同账户资金动作"),
            Map.entry("SHORT_WINDOW_COMPOSITE", "短窗口资金汇总"),
            Map.entry("ORGANIZED_ROLE_ARRANGEMENT", "组织与角色安排"),
            Map.entry("THRESHOLD_AMOUNT_ACTIVITY", "阈值附近资金活动"),
            Map.entry("THRESHOLD_STRUCTURING", "阈值下拆分交易"),
            Map.entry("ROUND_AMOUNT_STRUCTURING", "整数金额拆分交易"),
            Map.entry("HIGH_FREQUENCY_DIGITAL_PAYMENT", "高频数字支付"),
            Map.entry("HIGH_FREQUENCY_PASS_THROUGH", "高频收付后快速转出"),
            Map.entry("CONTEXTUAL_RISK_MISMATCH", "交易行为与客户画像不符"),
            Map.entry("CASH_DEPOSIT", "现金存入"),
            Map.entry("ACCOUNT_MULE_USAGE", "银行卡提供与代持"),
            Map.entry("CASH_WITHDRAWAL", "取现"),
            Map.entry("CASH_DELIVERY", "现金交付"),
            Map.entry("ONWARD_FUNDS_TRANSFER", "后续资金转移"),
            Map.entry("PRECIOUS_METAL_CONVERSION", "贵金属转换"),
            Map.entry("DIGITAL_ASSET_CONVERSION", "数字资产转换"),
            Map.entry("DEFI_ACTIVITY", "DeFi 活动"),
            Map.entry("CROSS_CHAIN_TRANSFER", "跨链转移"),
            Map.entry("MULTI_ACCOUNT_TRANSFER", "多账户转移"),
            Map.entry("MULTI_ACCOUNT_LAYERING", "多账户分层转移"),
            Map.entry("CROSS_BORDER_MULTI_ACCOUNT_TRANSFER", "跨境多账户转移"),
            Map.entry("LOAN_JUSTIFICATION", "贷款名义"),
            Map.entry("FICTITIOUS_TRADE", "虚构交易"),
            Map.entry("SERVICE_FEE_JUSTIFICATION", "服务费名义"),
            Map.entry("LEGITIMATE_ASSET_INVESTMENT", "合法资产投资"),
            Map.entry("OPERATIONAL_EVASION", "操作规避")
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RiskAnalyticsGateway gateway;
    private final TuGraphStructuredWriter graphWriter;
    private final IndicatorExecutionService indicatorExecutionService;

    public CaseMatterExplanationService(JdbcTemplate jdbc, ObjectMapper mapper,
                                        RiskAnalyticsGateway gateway,
                                        TuGraphStructuredWriter graphWriter,
                                        IndicatorExecutionService indicatorExecutionService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.gateway = gateway;
        this.graphWriter = graphWriter;
        this.indicatorExecutionService = indicatorExecutionService;
    }

    public String caseBankCode(String caseId) {
        return jdbc.queryForObject("SELECT bank_code FROM cf_risk_case WHERE case_id=? AND deleted=false",
                String.class, caseId);
    }

    @Transactional
    public Map<String, Object> refresh(String caseId) {
        Map<String, Object> riskCase = jdbc.queryForMap("""
            SELECT case_id,bank_code,workspace_id,case_source,case_type,case_name,description,scenario_code
            FROM cf_risk_case WHERE case_id=? AND deleted=false
            """, caseId);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("caseId", caseId);
        boolean textCase = "TEXT_CASE".equals(Objects.toString(riskCase.get("case_source")))
                || "DOCUMENT_EXTRACTED".equals(Objects.toString(riskCase.get("case_type")));
        payload.put("sourceType", textCase ? "TEXT" : "STRUCTURED");
        payload.put("description", Objects.toString(riskCase.get("description"), ""));
        payload.put("scenarioCode", Objects.toString(riskCase.get("scenario_code"), ""));
        List<Map<String, Object>> caseEvents = events(caseId);
        List<Map<String, Object>> caseTransactions = textCase ? List.of() : transactions(caseId);
        payload.set("events", mapper.valueToTree(caseEvents));
        if (!textCase) payload.set("transactions", mapper.valueToTree(caseTransactions));
        Map<String, Object> indicatorFeatures = new LinkedHashMap<>();
        indicatorFeatures.put("events", caseEvents);
        indicatorFeatures.put("transactions", caseTransactions);
        indicatorFeatures.put("eventCount", caseEvents.size());
        indicatorFeatures.put("transactionCount", caseTransactions.size());
        indicatorFeatures.put("_provenance",
                indicatorProvenance(caseEvents, caseTransactions));
        List<Map<String, Object>> indicatorResults = new ArrayList<>(
                indicatorExecutionService.execute(
                Objects.toString(riskCase.get("bank_code")),
                Objects.toString(riskCase.get("scenario_code"), "CASE_EXPLANATION"),
                "CASE", caseId,
                List.of("BG_CASE_EVENT_COUNT", "BG_CASE_TRANSACTION_COUNT"),
                indicatorFeatures));
        payload.set("indicatorResults", mapper.valueToTree(indicatorResults));

        JsonNode result = gateway.execute("CASE_MATTER_EXPLANATION", payload);
        indicatorResults.addAll(indicatorExecutionService.matchSemanticObservations(
                Objects.toString(riskCase.get("bank_code")),
                Objects.toString(riskCase.get("scenario_code"), "CASE_EXPLANATION"),
                "CASE", caseId, result.path("semanticObservations"),
                result.path("inputSnapshotSha256").asText()));
        attachIndicatorResultRefs(result, indicatorResults);
        pruneReasoningWithoutIndicatorHits(result);
        calibrateReasoningConfidence(result, indicatorResults, caseEvents);
        rebuildTechniqueOccurrences(caseId, result, indicatorResults, caseEvents);
        attachCoreReasoningRefs(result);
        enrichCaseSpecificNarratives(caseId, result, indicatorResults);
        persist(riskCase, result);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("caseId", caseId);
        response.put("inputSnapshotSha256", result.path("inputSnapshotSha256").asText());
        response.put("matterCount", result.path("matters").size());
        response.put("behaviorPatternCount", result.path("behaviorPatternOccurrences").size());
        response.put("riskEventCount", result.path("riskEvents").size());
        response.put("alternativeExplanationCount", result.path("alternativeExplanations").size());
        response.put("investigationHypothesisCount", result.path("investigationHypotheses").size());
        response.put("techniqueCount", result.path("techniqueOccurrences").size());
        response.put("reviewSuggestionCount", result.path("reviewSuggestions").size());
        response.put("indicatorResultCount", indicatorResults.size());
        response.put("algorithm", mapper.convertValue(result.path("algorithm"), Map.class));
        try {
            response.put("graphProjection", graphWriter.writeReasoningGraph(caseId));
        } catch (Exception ex) {
            response.put("graphProjection", Map.of(
                    "status", "PENDING_RETRY", "message", ex.getMessage()));
        }
        return response;
    }

    private void calibrateReasoningConfidence(
            JsonNode result, List<Map<String, Object>> indicatorResults,
            List<Map<String, Object>> caseEvents) {
        Map<String, Double> eventConfidence = new LinkedHashMap<>();
        for (Map<String, Object> event : caseEvents) {
            String id = Objects.toString(event.get("eventId"), "");
            if (!id.isBlank()) eventConfidence.put(id,
                    bounded(number(event.get("confidence"), 0.70)));
        }
        Map<String, Double> indicatorScores = new LinkedHashMap<>();
        Map<String, Integer> indicatorEvidenceCounts = new LinkedHashMap<>();
        for (Map<String, Object> item : indicatorResults) {
            String id = Objects.toString(item.get("calculationId"), "");
            if (id.isBlank()) continue;
            double score = number(item.get("semanticScore"), Double.NaN);
            Object explanation = item.get("explanation");
            int evidenceCount = 0;
            boolean hasLineage = false;
            if (explanation instanceof Map<?, ?> values) {
                evidenceCount = size(values.get("evidenceRefs"));
                hasLineage = size(values.get("eventRefs")) > 0
                        || size(values.get("transactionRefs")) > 0;
                if (Double.isNaN(score)) {
                    score = number(values.get("semanticScore"), Double.NaN);
                }
            }
            if (Double.isNaN(score)) score = hasLineage ? 0.90 : 0.55;
            indicatorScores.put(id, bounded(score));
            indicatorEvidenceCounts.put(id, evidenceCount);
        }
        Map<String, JsonNode> patternsById = new LinkedHashMap<>();
        for (JsonNode pattern : result.path("behaviorPatternOccurrences")) {
            if (!(pattern instanceof ObjectNode object)) continue;
            List<String> eventRefs = textValues(pattern.path("eventRefs"));
            List<String> indicatorRefs = textValues(
                    pattern.path("indicatorResultRefs"));
            double eventQuality = averageRefs(
                    eventRefs, eventConfidence, eventRefs.isEmpty() ? 0.55 : 0.70);
            double indicatorSupport = averageRefs(
                    indicatorRefs, indicatorScores, 0.55);
            double constraintCoverage = constraintCoverage(pattern);
            int evidenceCount = indicatorRefs.stream()
                    .mapToInt(ref -> indicatorEvidenceCounts.getOrDefault(ref, 0))
                    .sum();
            double evidenceDiversity = Math.min(1.0,
                    (eventRefs.size() + Math.min(3, evidenceCount)) / 5.0);
            double fact = weighted(
                    eventQuality, 0.55,
                    evidenceDiversity, 0.25,
                    indicatorSupport, 0.20);
            double patternScore = weighted(
                    fact, 0.30,
                    indicatorSupport, 0.35,
                    constraintCoverage, 0.25,
                    evidenceDiversity, 0.10);
            object.put("factConfidence", fact);
            object.put("patternConfidence", patternScore);
            object.set("confidenceBreakdown", scoreBreakdown(
                    "事实置信度", fact,
                    factor("事件可靠度", eventQuality, 0.55),
                    factor("证据覆盖度", evidenceDiversity, 0.25),
                    factor("指标支持度", indicatorSupport, 0.20),
                    "模式置信度", patternScore,
                    factor("事实置信度", fact, 0.30),
                    factor("指标/语义匹配度", indicatorSupport, 0.35),
                    factor("约束命中率", constraintCoverage, 0.25),
                    factor("证据多样性", evidenceDiversity, 0.10)));
            patternsById.put(pattern.path("occurrenceId").asText(), pattern);
        }
        Map<String, JsonNode> risksById = new LinkedHashMap<>();
        for (JsonNode risk : result.path("riskEvents")) {
            if (!(risk instanceof ObjectNode object)) continue;
            List<JsonNode> linkedPatterns = new ArrayList<>();
            for (String ref : textValues(risk.path("behaviorOccurrenceRefs"))) {
                JsonNode pattern = patternsById.get(ref);
                if (pattern != null) linkedPatterns.add(pattern);
            }
            double fact = averageNodes(linkedPatterns, "factConfidence", 0.55);
            double patternScore = averageNodes(
                    linkedPatterns, "patternConfidence", 0.55);
            Set<String> linkedIndicators = new LinkedHashSet<>();
            linkedPatterns.forEach(pattern ->
                    linkedIndicators.addAll(textValues(
                            pattern.path("indicatorResultRefs"))));
            double indicatorSupport = averageRefs(
                    new ArrayList<>(linkedIndicators), indicatorScores, 0.55);
            double eventCoverage = Math.min(1.0,
                    textValues(risk.path("eventRefs")).size() / 3.0);
            double riskScore = weighted(
                    fact, 0.30,
                    patternScore, 0.40,
                    indicatorSupport, 0.20,
                    eventCoverage, 0.10);
            object.put("factConfidence", fact);
            object.put("riskConfidence", riskScore);
            object.set("confidenceBreakdown", scoreBreakdown(
                    "风险置信度", riskScore,
                    factor("事实置信度", fact, 0.30),
                    factor("行为模式置信度", patternScore, 0.40),
                    factor("指标/语义支持度", indicatorSupport, 0.20),
                    factor("事件覆盖度", eventCoverage, 0.10)));
            risksById.put(risk.path("riskEventId").asText(), risk);
        }
        for (JsonNode alternative : result.path("alternativeExplanations")) {
            if (!(alternative instanceof ObjectNode object)) continue;
            int supporting = alternative.path("supportingEvidenceRefs").size();
            int contradicting = alternative.path("contradictingEvidenceRefs").size();
            double supportRatio = supporting
                    / (double) Math.max(1, supporting + contradicting);
            JsonNode target = risksById.get(
                    alternative.path("targetRiskEventId").asText());
            double inverseRisk = target == null ? 0.45
                    : 1.0 - target.path("riskConfidence").asDouble(0.55);
            double confidence = weighted(
                    supportRatio, 0.65,
                    inverseRisk, 0.35);
            object.put("confidence", confidence);
            object.set("confidenceBreakdown", scoreBreakdown(
                    "替代解释支持度", confidence,
                    factor("支持证据占比", supportRatio, 0.65),
                    factor("现有风险结论可反驳空间", inverseRisk, 0.35)));
        }
    }

    private Map<String, Object> indicatorProvenance(
            List<Map<String, Object>> caseEvents,
            List<Map<String, Object>> caseTransactions) {
        List<String> eventRefs = caseEvents.stream()
                .map(event -> Objects.toString(event.get("eventId"), ""))
                .filter(value -> !value.isBlank())
                .distinct().toList();
        List<Object> evidenceRefs = new ArrayList<>();
        Set<String> evidenceKeys = new LinkedHashSet<>();
        caseEvents.forEach(event -> appendLineageValues(
                evidenceRefs, evidenceKeys, event.get("evidenceRefs")));
        List<String> transactionRefs = caseTransactions.stream()
                .map(transaction -> {
                    String record = Objects.toString(
                            transaction.get("sourceRecordId"), "");
                    if (!record.isBlank()) return record;
                    String line = Objects.toString(
                            transaction.get("sourceLine"), "");
                    return line.isBlank() ? "" : "TX-LINE-" + line;
                })
                .filter(value -> !value.isBlank())
                .distinct().toList();
        Map<String, Object> eventLineage = new LinkedHashMap<>();
        eventLineage.put("eventRefs", eventRefs);
        eventLineage.put("evidenceRefs", evidenceRefs);
        Map<String, Object> transactionLineage = new LinkedHashMap<>();
        transactionLineage.put("eventRefs", eventRefs);
        transactionLineage.put("transactionRefs", transactionRefs);
        transactionLineage.put("evidenceRefs", evidenceRefs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", eventLineage);
        result.put("eventCount", eventLineage);
        result.put("transactions", transactionLineage);
        result.put("transactionCount", transactionLineage);
        return result;
    }

    private void appendLineageValues(List<Object> target, Set<String> keys,
                                     Object raw) {
        JsonNode values = mapper.valueToTree(raw);
        if (!values.isArray()) return;
        values.forEach(value -> {
            Object converted = mapper.convertValue(value, Object.class);
            String key = value.toString();
            if (keys.add(key)) target.add(converted);
        });
    }

    private void attachIndicatorResultRefs(JsonNode result,
                                           List<Map<String, Object>> indicatorResults) {
        Map<String, String> calculationByCode = new LinkedHashMap<>();
        Map<String, Set<String>> observationsByCalculation = new LinkedHashMap<>();
        indicatorResults.forEach(item -> {
            String code = Objects.toString(item.get("indicatorCode"), "");
            String id = Objects.toString(item.get("calculationId"), "");
            if (!code.isBlank() && !id.isBlank()) calculationByCode.put(code, id);
            Object sourceCodes = item.get("sourceObservationCodes");
            if (sourceCodes instanceof List<?> values) {
                Set<String> observations = new LinkedHashSet<>();
                for (Object value : values) {
                    observations.add(Objects.toString(value));
                }
                if (!id.isBlank()) observationsByCalculation.put(id, observations);
            }
        });
        for (String field : List.of(
                "behaviorPatternOccurrences", "matters", "techniqueOccurrences")) {
            for (JsonNode item : result.path(field)) {
                if (!(item instanceof ObjectNode object)) continue;
                ArrayNode refs = mapper.createArrayNode();
                item.path("indicatorResultRefs").forEach(refs::add);
                for (JsonNode code : item.path("indicatorCodes")) {
                    String calculationId = calculationByCode.get(code.asText());
                    boolean exists = false;
                    for (JsonNode current : refs) {
                        if (current.asText().equals(calculationId)) {
                            exists = true;
                            break;
                        }
                    }
                    if (calculationId != null && !exists) refs.add(calculationId);
                }
                Set<String> requiredObservations = new LinkedHashSet<>();
                item.path("observationCodes").forEach(code ->
                        requiredObservations.add(code.asText()));
                if (!requiredObservations.isEmpty()) {
                    Set<String> coveredObservations = new LinkedHashSet<>();
                    List<String> supportingCalculations = new ArrayList<>();
                    for (Map.Entry<String, Set<String>> entry
                            : observationsByCalculation.entrySet()) {
                        Set<String> contribution = new LinkedHashSet<>(entry.getValue());
                        contribution.retainAll(requiredObservations);
                        if (contribution.isEmpty()) continue;
                        coveredObservations.addAll(contribution);
                        supportingCalculations.add(entry.getKey());
                    }
                    // A pattern may be supported jointly by several formal
                    // indicator hits.  Retain the references only when their
                    // combined observations satisfy the complete contract.
                    if (coveredObservations.containsAll(requiredObservations)) {
                        for (String calculationId : supportingCalculations) {
                            boolean exists = false;
                            for (JsonNode current : refs) {
                                if (current.asText().equals(calculationId)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) refs.add(calculationId);
                        }
                    }
                }
                object.set("indicatorResultRefs", refs);
            }
        }
    }

    private void pruneReasoningWithoutIndicatorHits(JsonNode result) {
        ArrayNode matters = result.withArray("matters");
        for (int index = matters.size() - 1; index >= 0; index--) {
            JsonNode matter = matters.get(index);
            boolean evidenceBackedFact = "FACT_SUMMARY".equals(
                    matter.path("details").path("productClass").asText())
                    && !matter.path("evidenceRefs").isEmpty()
                    && "REPORTED".equals(matter.path("certainty").asText());
            if (matter.path("indicatorResultRefs").isEmpty() && !evidenceBackedFact) {
                matters.remove(index);
            }
        }
        Set<String> retainedPatterns = new LinkedHashSet<>();
        ArrayNode patterns = result.withArray("behaviorPatternOccurrences");
        for (int index = patterns.size() - 1; index >= 0; index--) {
            JsonNode pattern = patterns.get(index);
            if (pattern.path("indicatorResultRefs").isEmpty()) {
                patterns.remove(index);
            } else {
                retainedPatterns.add(pattern.path("occurrenceId").asText());
            }
        }
        Set<String> retainedRisks = new LinkedHashSet<>();
        ArrayNode risks = result.withArray("riskEvents");
        for (int index = risks.size() - 1; index >= 0; index--) {
            JsonNode risk = risks.get(index);
            if (!(risk instanceof ObjectNode object)) continue;
            ArrayNode refs = mapper.createArrayNode();
            risk.path("behaviorOccurrenceRefs").forEach(item -> {
                if (retainedPatterns.contains(item.asText())) refs.add(item.asText());
            });
            object.set("behaviorOccurrenceRefs", refs);
            if (refs.isEmpty()) {
                risks.remove(index);
            } else {
                retainedRisks.add(risk.path("riskEventId").asText());
            }
        }
        ArrayNode alternatives = result.withArray("alternativeExplanations");
        for (int index = alternatives.size() - 1; index >= 0; index--) {
            String targetRisk = alternatives.get(index)
                    .path("targetRiskEventId").asText();
            if (!targetRisk.isBlank() && !retainedRisks.contains(targetRisk)) {
                alternatives.remove(index);
            }
        }
        ArrayNode investigations = result.withArray("investigationHypotheses");
        for (int index = investigations.size() - 1; index >= 0; index--) {
            if (!retainedRisks.contains(
                    investigations.get(index).path("riskEventId").asText())) {
                investigations.remove(index);
            }
        }
    }

    private void rebuildTechniqueOccurrences(
            String caseId, JsonNode result,
            List<Map<String, Object>> indicatorResults,
            List<Map<String, Object>> caseEvents) {
        Map<String, Double> caseEventConfidence = new LinkedHashMap<>();
        caseEvents.forEach(event -> {
            String id = Objects.toString(event.get("eventId"), "");
            if (!id.isBlank()) caseEventConfidence.put(id,
                    bounded(number(event.get("confidence"), 0.70)));
        });
        Map<String, Map<String, Object>> resultById = new LinkedHashMap<>();
        List<String> matchedIndicatorCodes = new ArrayList<>();
        for (Map<String, Object> item : indicatorResults) {
            String id = Objects.toString(item.get("calculationId"), "");
            String code = Objects.toString(item.get("indicatorCode"), "");
            if (!id.isBlank()) resultById.put(id, item);
            if (!code.isBlank()) matchedIndicatorCodes.add(code);
        }
        ArrayNode output = result.withArray("techniqueOccurrences");
        output.removeAll();
        if (matchedIndicatorCodes.isEmpty()) return;
        Map<String, List<Map<String, Object>>> relationsByTechnique = new LinkedHashMap<>();
        for (String code : matchedIndicatorCodes.stream().distinct().toList()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT source_code,source_version,target_code
                FROM knowledge_asset_relation
                WHERE source_type='TECHNIQUE' AND relation_type='TECHNIQUE_HAS_INDICATOR'
                  AND target_type='INDICATOR' AND target_code=?
                  AND status='ACTIVE' AND review_status='APPROVED'
                  AND (effective_from IS NULL OR effective_from<=CURRENT_TIMESTAMP)
                  AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP)
                ORDER BY source_code,source_version
                """, code);
            rows.forEach(row -> relationsByTechnique
                    .computeIfAbsent(Objects.toString(row.get("source_code")),
                            ignored -> new ArrayList<>()).add(row));
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : relationsByTechnique.entrySet()) {
            String techniqueCode = entry.getKey();
            Set<String> linkedCodes = new LinkedHashSet<>();
            entry.getValue().forEach(row ->
                    linkedCodes.add(Objects.toString(row.get("target_code"))));
            Set<String> calculationIds = new LinkedHashSet<>();
            Set<String> eventRefs = new LinkedHashSet<>();
            Set<String> techniqueObservations = new LinkedHashSet<>();
            double score = 0;
            int scoreCount = 0;
            for (Map<String, Object> item : indicatorResults) {
                if (!linkedCodes.contains(Objects.toString(item.get("indicatorCode")))) continue;
                calculationIds.add(Objects.toString(item.get("calculationId")));
                techniqueObservations.addAll(indicatorObservationCodes(item));
                Object semanticScore = item.get("semanticScore");
                double currentScore = number(semanticScore, Double.NaN);
                Object explanation = item.get("explanation");
                if (explanation instanceof Map<?, ?> map
                        && map.get("eventRefs") instanceof List<?> values) {
                    values.forEach(value -> eventRefs.add(Objects.toString(value)));
                    if (Double.isNaN(currentScore)) {
                        currentScore = values.isEmpty() ? 0.55 : 0.90;
                    }
                }
                if (Double.isNaN(currentScore)) currentScore = 0.55;
                score += currentScore;
                scoreCount++;
            }
            Set<String> patternRefs = new LinkedHashSet<>();
            for (JsonNode pattern : result.path("behaviorPatternOccurrences")) {
                boolean indicatorLinked = false;
                for (JsonNode ref : pattern.path("indicatorResultRefs")) {
                    if (calculationIds.contains(ref.asText())) indicatorLinked = true;
                }
                Set<String> requiredObservations = new LinkedHashSet<>();
                for (JsonNode constraint : pattern.path("matchedConstraints")) {
                    if (constraint.path("required").asBoolean(true)) {
                        String code = constraint.path("constraint").asText();
                        if (!code.isBlank()) requiredObservations.add(code);
                    }
                }
                boolean contractCovered = requiredObservations.isEmpty()
                        || techniqueObservations.containsAll(requiredObservations);
                if (indicatorLinked && contractCovered) {
                    patternRefs.add(pattern.path("occurrenceId").asText());
                }
            }
            Set<String> riskRefs = new LinkedHashSet<>();
            for (JsonNode risk : result.path("riskEvents")) {
                for (JsonNode patternRef : risk.path("behaviorOccurrenceRefs")) {
                    if (patternRefs.contains(patternRef.asText())) {
                        riskRefs.add(risk.path("riskEventId").asText());
                    }
                }
            }
            String version = Objects.toString(
                    entry.getValue().get(0).get("source_version"), "1");
            ObjectNode occurrence = mapper.createObjectNode();
            occurrence.put("occurrenceId", "TO-SEM-"
                    + sha256(caseId + "|" + techniqueCode + "|" + calculationIds)
                    .substring(0, 24).toUpperCase());
            occurrence.put("techniqueCode", techniqueCode);
            occurrence.put("techniqueVersion", version);
            occurrence.put("decision", "CANDIDATE");
            occurrence.put("status", "CANDIDATE");
            occurrence.set("eventRefs", mapper.valueToTree(eventRefs));
            occurrence.set("behaviorOccurrenceRefs", mapper.valueToTree(patternRefs));
            occurrence.set("indicatorResultRefs", mapper.valueToTree(calculationIds));
            occurrence.set("riskEventRefs", mapper.valueToTree(riskRefs));
            double indicatorSupport = scoreCount == 0 ? 0.55 : score / scoreCount;
            List<JsonNode> linkedPatterns = new ArrayList<>();
            for (JsonNode pattern : result.path("behaviorPatternOccurrences")) {
                if (patternRefs.contains(pattern.path("occurrenceId").asText())) {
                    linkedPatterns.add(pattern);
                }
            }
            double fact = linkedPatterns.isEmpty()
                    ? averageRefs(new ArrayList<>(eventRefs),
                            caseEventConfidence, 0.55)
                    : averageNodes(linkedPatterns, "factConfidence", 0.55);
            double patternSupport = averageNodes(
                    linkedPatterns, "patternConfidence", 0.55);
            List<JsonNode> linkedRisks = new ArrayList<>();
            for (JsonNode risk : result.path("riskEvents")) {
                if (riskRefs.contains(risk.path("riskEventId").asText())) {
                    linkedRisks.add(risk);
                }
            }
            double linkedRisk = averageNodes(linkedRisks, "riskConfidence", 0);
            double mapping = weighted(
                    indicatorSupport, 0.70,
                    patternSupport, 0.20,
                    1.0, 0.10);
            double risk = weighted(
                    linkedRisk, 0.50,
                    mapping, 0.30,
                    fact, 0.20);
            occurrence.put("factConfidence", fact);
            occurrence.put("mappingConfidence", mapping);
            occurrence.put("riskConfidence", risk);
            occurrence.set("confidenceBreakdown", scoreBreakdown(
                    "事实置信度", fact,
                    factor(linkedPatterns.isEmpty()
                                    ? "关联事件可靠度" : "关联模式事实置信度",
                            fact, 1.0),
                    "AMLTRIX 映射置信度", mapping,
                    factor("指标/语义匹配度", indicatorSupport, 0.70),
                    factor("行为模式支持度", patternSupport, 0.20),
                    factor("已审核知识关系", 1.0, 0.10),
                    "技术风险置信度", risk,
                    factor("关联风险结论", linkedRisk, 0.50),
                    factor("技术映射置信度", mapping, 0.30),
                    factor("事实置信度", fact, 0.20)));
            occurrence.put("evidenceStrength", "E2");
            occurrence.put("knowledgeAuthorityLevel", "K4");
            occurrence.put("assertionMode", "INDICATOR_KNOWLEDGE_BINDING");
            occurrence.put("explanation", "");
            output.add(occurrence);
        }
    }

    /**
     * Turn machine-oriented reasoning references into a case-specific explanation.
     * The narrative deliberately separates observed features from inferred meaning
     * and states what evidence could weaken or strengthen the inference.
     */
    private void enrichCaseSpecificNarratives(String caseId, JsonNode result,
                                              List<Map<String, Object>> indicatorResults) {
        Map<String, String> indicatorNameById = new LinkedHashMap<>();
        for (Map<String, Object> item : indicatorResults) {
            String id = Objects.toString(item.get("calculationId"), "");
            String name = Objects.toString(item.get("indicatorName"),
                    Objects.toString(item.get("indicatorCode"), ""));
            if (!id.isBlank() && !name.isBlank()) indicatorNameById.put(id, name);
        }

        Map<String, String> eventNameById = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT event_id,event_name
                FROM cf_risk_event
                WHERE case_id=? AND deleted=false
                ORDER BY event_time NULLS LAST,event_id
                """, caseId)) {
            eventNameById.put(Objects.toString(row.get("event_id")),
                    Objects.toString(row.get("event_name"), "未命名事件"));
        }

        Map<String, String> patternNameById = new LinkedHashMap<>();
        for (JsonNode pattern : result.path("behaviorPatternOccurrences")) {
            String occurrenceId = pattern.path("occurrenceId").asText();
            String patternCode = pattern.path("patternCode").asText();
            String patternName = pattern.path("patternName").asText();
            if (patternName.isBlank() && !patternCode.isBlank()) {
                List<String> names = jdbc.query("""
                        SELECT pattern_name FROM event_pattern_definition
                        WHERE pattern_code=? ORDER BY created_at DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getString(1), patternCode);
                patternName = names.isEmpty() ? patternCode : names.get(0);
            }
            if (!occurrenceId.isBlank()) patternNameById.put(occurrenceId, patternName);
        }

        for (JsonNode technique : result.path("techniqueOccurrences")) {
            if (!(technique instanceof ObjectNode object)) continue;
            String code = technique.path("techniqueCode").asText();
            List<String> techniqueNames = jdbc.query("""
                    SELECT term_name FROM ontology_term
                    WHERE namespace='AMLTRIX_TECHNIQUE' AND term_code=? AND status='ACTIVE'
                    ORDER BY term_version DESC LIMIT 1
                    """, (rs, rowNum) -> rs.getString(1), code);
            String techniqueName = techniqueNames.isEmpty() ? code : techniqueNames.get(0);
            List<String> events = namesFromRefs(technique.path("eventRefs"), eventNameById, 3);
            List<String> indicators = namesFromRefs(
                    technique.path("indicatorResultRefs"), indicatorNameById, 3);
            List<String> patterns = namesFromRefs(
                    technique.path("behaviorOccurrenceRefs"), patternNameById, 2);
            String observed = events.isEmpty()
                    ? "本案标准化事件"
                    : quoteNames(events);
            String indicatorText = indicators.isEmpty()
                    ? "相关正式指标"
                    : quoteNames(indicators);
            String patternText = patterns.isEmpty()
                    ? ""
                    : "，并形成" + quoteNames(patterns) + "行为组合";
            object.put("techniqueName", techniqueName);
            object.put("explanation",
                    observed + patternText + "，命中" + indicatorText
                            + "；这些可观察特征与 AMLTRIX " + code + "“"
                            + techniqueName + "”的技术特征一致，因此形成技术候选。"
                            + "该映射说明行为特征相符，不单独证明主观目的；"
                            + "若原始流水、主体关系或业务凭证不能支持上述事件之间的关联，"
                            + "应降低或排除该技术判断。");
        }

        Map<String, String> riskFeatureById = new LinkedHashMap<>();
        Map<String, Set<String>> matterTypesByRisk = new LinkedHashMap<>();
        for (JsonNode risk : result.path("riskEvents")) {
            String riskId = risk.path("riskEventId").asText();
            String riskTitle = risk.path("title").asText("当前风险判断");
            List<String> eventNames = namesFromRefs(risk.path("eventRefs"), eventNameById, 3);
            List<String> patternNames = namesFromRefs(
                    risk.path("behaviorOccurrenceRefs"), patternNameById, 2);
            List<String> summaries = new ArrayList<>();
            Set<String> scopedTypes = new LinkedHashSet<>();
            for (JsonNode matter : result.path("matters")) {
                if (!containsRef(matter.path("riskEventRefs"), riskId)) continue;
                String summary = compact(matter.path("summary").asText(), 60);
                if (!summary.isBlank() && summaries.size() < 2) summaries.add(summary);
                String type = matter.path("matterType").asText();
                if (!type.isBlank()) scopedTypes.add(type);
            }
            List<String> clauses = new ArrayList<>();
            if (!summaries.isEmpty()) clauses.add("事实特征为" + quoteNames(summaries));
            if (!eventNames.isEmpty()) clauses.add("关联事件为" + quoteNames(eventNames));
            if (!patternNames.isEmpty()) clauses.add("形成" + quoteNames(patternNames) + "行为模式");
            if (clauses.isEmpty()) clauses.add("由当前标准化事件与正式指标共同支持");
            riskFeatureById.put(riskId,
                    "“" + compact(riskTitle, 32) + "”判断" + String.join("，", clauses));
            matterTypesByRisk.put(riskId, scopedTypes);
        }
        String primaryRiskId = riskFeatureById.keySet().stream().findFirst().orElse("");

        for (JsonNode alternative : result.path("alternativeExplanations")) {
            if (!(alternative instanceof ObjectNode object)) continue;
            String title = alternative.path("title").asText("正常业务或数据质量解释");
            String riskId = alternative.path("targetRiskEventId").asText(primaryRiskId);
            String focusedFeature = riskFeatureById.getOrDefault(
                    riskId, "当前风险判断由已入库的标准化事件和行为模式支持");
            List<String> requiredEvidence = recommendedEvidence(
                    matterTypesByRisk.getOrDefault(riskId, Set.of()));
            object.put("summary",
                    focusedFeature + "。替代解释“" + title + "”成立的边界是："
                            + String.join("、", requiredEvidence)
                            + "能够逐项解释上述事件的主体、金额与时序。"
                            + "满足该条件时应降低当前风险判断；若凭证缺失、主体关系不明，"
                            + "或资金链与声明目的不一致，则该替代解释被削弱。");
        }

        for (JsonNode hypothesis : result.path("investigationHypotheses")) {
            if (!(hypothesis instanceof ObjectNode object)) continue;
            String riskId = hypothesis.path("riskEventId").asText(primaryRiskId);
            String focusedFeature = riskFeatureById.getOrDefault(
                    riskId, "当前风险判断由已入库的标准化事件和行为模式支持");
            Set<String> scopedTypes = matterTypesByRisk.getOrDefault(riskId, Set.of());
            List<String> requiredEvidence = recommendedEvidence(scopedTypes);
            List<String> actions = recommendedActions(scopedTypes);
            object.put("hypothesis",
                    focusedFeature + "；需验证这些事件是否由同一实际控制关系或共同经济目的"
                            + "连接，并构成连续、可复现的资金行为链。");
            object.set("evidenceNeeded", mapper.valueToTree(requiredEvidence));
            object.set("recommendedActions", mapper.valueToTree(actions));
        }

        for (JsonNode suggestion : result.path("reviewSuggestions")) {
            if (!(suggestion instanceof ObjectNode object)) continue;
            String focusedFeature = riskFeatureById.getOrDefault(
                    primaryRiskId, "当前风险判断由已入库的标准化事件和行为模式支持");
            Set<String> scopedTypes = matterTypesByRisk.getOrDefault(primaryRiskId, Set.of());
            List<String> requiredEvidence = recommendedEvidence(scopedTypes);
            object.put("topic", "核验当前风险判断所依赖的事件连续性与主体控制关系");
            object.put("reason",
                    focusedFeature + "。当前证据已支持形成候选判断，"
                            + "但尚未完成原始记录、实际控制关系与声明经济目的的交叉核验。");
            object.put("expectedMaterial", String.join("、", requiredEvidence));
        }
    }

    private boolean containsRef(JsonNode refs, String expected) {
        if (!refs.isArray() || expected == null || expected.isBlank()) return false;
        for (JsonNode ref : refs) {
            if (expected.equals(ref.asText())) return true;
        }
        return false;
    }

    private List<String> namesFromRefs(JsonNode refs, Map<String, String> names, int limit) {
        List<String> output = new ArrayList<>();
        if (!refs.isArray()) return output;
        for (JsonNode ref : refs) {
            String name = names.get(ref.asText());
            if (name != null && !name.isBlank() && !output.contains(name)) output.add(name);
            if (output.size() >= limit) break;
        }
        return output;
    }

    private String quoteNames(List<String> names) {
        return names.stream().map(name -> "“" + compact(name, 36) + "”")
                .reduce((left, right) -> left + "、" + right).orElse("");
    }

    private String compact(String value, int limit) {
        String clean = Objects.toString(value, "")
                .replace("材料记载", "")
                .replace("材料反映", "")
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }

    private List<String> recommendedEvidence(Set<String> matterTypes) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("涉案账户逐笔流水与余额变化");
        if (matterTypes.contains("ACCOUNT_MULE_USAGE")
                || matterTypes.contains("MULTI_ACCOUNT_TRANSFER")
                || matterTypes.contains("MULTI_ACCOUNT_LAYERING")) {
            values.add("开户资料、实际控制人及设备登录记录");
        }
        if (matterTypes.contains("CASH_WITHDRAWAL")
                || matterTypes.contains("CASH_DELIVERY")) {
            values.add("柜面或ATM取现记录、影像及现金交付依据");
        }
        if (matterTypes.contains("DIGITAL_ASSET_CONVERSION")
                || matterTypes.contains("DEFI_ACTIVITY")
                || matterTypes.contains("CROSS_CHAIN_TRANSFER")) {
            values.add("交易所账户、链上地址及资金进出记录");
        }
        if (matterTypes.contains("PRECIOUS_METAL_CONVERSION")) {
            values.add("贵金属订单、发票及交付或回购记录");
        }
        values.add("合同、发票、交易备注等经济目的凭证");
        return new ArrayList<>(values).subList(0, Math.min(values.size(), 4));
    }

    private List<String> recommendedActions(Set<String> matterTypes) {
        List<String> actions = new ArrayList<>();
        actions.add("按时间排序还原入账、转账、取现或资产转换的完整资金链");
        actions.add("穿透核验账户持有人、实际操作人、交易对手与最终受益人");
        if (matterTypes.contains("DIGITAL_ASSET_CONVERSION")
                || matterTypes.contains("DEFI_ACTIVITY")
                || matterTypes.contains("CROSS_CHAIN_TRANSFER")) {
            actions.add("关联交易所及链上地址，核对法币与数字资产转换节点");
        } else if (matterTypes.contains("PRECIOUS_METAL_CONVERSION")) {
            actions.add("核对贵金属购买、交付和变现记录与资金时间是否一致");
        } else {
            actions.add("将业务凭证与金额、频率、时序和对手关系逐项交叉验证");
        }
        return actions;
    }

    private void attachCoreReasoningRefs(JsonNode result) {
        Map<String, JsonNode> risks = new LinkedHashMap<>();
        for (JsonNode risk : result.path("riskEvents")) {
            risks.put(risk.path("riskEventId").asText(), risk);
        }
        for (JsonNode technique : result.path("techniqueOccurrences")) {
            if (!(technique instanceof ObjectNode object)) continue;
            ArrayNode refs = mapper.createArrayNode();
            for (Map.Entry<String, JsonNode> entry : risks.entrySet()) {
                if (overlaps(technique.path("behaviorOccurrenceRefs"),
                        entry.getValue().path("behaviorOccurrenceRefs"))) refs.add(entry.getKey());
            }
            object.set("riskEventRefs", refs);
        }
        for (JsonNode matter : result.path("matters")) {
            if (!(matter instanceof ObjectNode object)) continue;
            ArrayNode patternRefs = mapper.createArrayNode();
            ArrayNode riskRefs = mapper.createArrayNode();
            JsonNode eventRefs = matter.path("eventRefs");
            for (JsonNode pattern : result.path("behaviorPatternOccurrences")) {
                if (overlaps(eventRefs, pattern.path("eventRefs"))) {
                    patternRefs.add(pattern.path("occurrenceId").asText());
                }
            }
            for (Map.Entry<String, JsonNode> entry : risks.entrySet()) {
                if (overlaps(patternRefs, entry.getValue().path("behaviorOccurrenceRefs"))
                        || overlaps(eventRefs, entry.getValue().path("eventRefs"))) {
                    riskRefs.add(entry.getKey());
                }
            }
            object.set("behaviorOccurrenceRefs", patternRefs);
            object.set("riskEventRefs", riskRefs);
            ArrayNode techniqueCodes = mapper.createArrayNode();
            Set<String> includedTechniques = new LinkedHashSet<>();
            for (JsonNode technique : result.path("techniqueOccurrences")) {
                boolean linked = overlaps(
                        matter.path("indicatorResultRefs"),
                        technique.path("indicatorResultRefs"))
                        || overlaps(patternRefs,
                        technique.path("behaviorOccurrenceRefs"))
                        || overlaps(riskRefs, technique.path("riskEventRefs"));
                String code = technique.path("techniqueCode").asText();
                if (linked && !code.isBlank() && includedTechniques.add(code)) {
                    techniqueCodes.add(code);
                }
            }
            object.set("techniqueCodes", techniqueCodes);
        }
    }

    private Set<String> indicatorObservationCodes(Map<String, Object> indicator) {
        Set<String> result = new LinkedHashSet<>();
        Object direct = indicator.get("sourceObservationCodes");
        if (direct instanceof List<?> values) {
            values.forEach(value -> result.add(Objects.toString(value)));
        }
        Object explanation = indicator.get("explanation");
        if (explanation instanceof Map<?, ?> map
                && map.get("sourceObservationCodes") instanceof List<?> values) {
            values.forEach(value -> result.add(Objects.toString(value)));
        }
        return result;
    }

    private boolean overlaps(JsonNode left, JsonNode right) {
        if (!left.isArray() || !right.isArray()) return false;
        for (JsonNode a : left) {
            for (JsonNode b : right) {
                if (a.asText().equals(b.asText())) return true;
            }
        }
        return false;
    }

    public Map<String, Object> reasoning(String caseId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indicatorResults", normalize(jdbc.queryForList("""
            WITH active_refs AS (
              SELECT jsonb_array_elements_text(indicator_result_refs) AS calculation_id
              FROM behavior_pattern_occurrence WHERE case_id=? AND status='ACTIVE'
              UNION
              SELECT jsonb_array_elements_text(indicator_result_refs)
              FROM technique_occurrence WHERE case_id=? AND status<>'SUPERSEDED'
              UNION
              SELECT jsonb_array_elements_text(indicator_result_refs)
              FROM case_matter_explanation WHERE case_id=? AND status='ACTIVE'
            )
            SELECT r.calculation_id AS "calculationId",r.indicator_code AS "indicatorCode",
                   r.indicator_version AS "indicatorVersion",d.indicator_name AS "indicatorName",
                   d.description,r.numeric_value AS "numericValue",r.text_value AS "textValue",
                   r.risk_level AS "riskLevel",r.explanation,
                   r.input_snapshot_sha256 AS "inputSnapshotSha256",
                   r.calculated_at AS "calculatedAt"
            FROM active_refs a
            JOIN indicator_calculation_result r ON r.calculation_id=a.calculation_id
            JOIN indicator_definition d ON d.indicator_code=r.indicator_code
            ORDER BY r.calculated_at,r.calculation_id
            """, caseId, caseId, caseId), "explanation"));
        result.put("behaviorPatternOccurrences", normalize(jdbc.queryForList("""
            SELECT o.occurrence_id AS "occurrenceId",o.pattern_code AS "patternCode",
                   d.pattern_name AS "patternName",
                   COALESCE(regexp_replace(v.pattern_payload->>'formalText','^材料中的',''),
                            d.pattern_name) AS "formalText",
                   COALESCE(v.pattern_payload->>'businessDescription',
                            v.pattern_payload->>'description','') AS "patternDescription",
                   o.pattern_version AS "patternVersion",o.pattern_class AS "patternClass",
                   o.event_refs AS "eventRefs",o.indicator_result_refs AS "indicatorResultRefs",
                   o.matched_constraints AS "matchedConstraints",
                   o.missing_constraints AS "missingConstraints",o.fact_confidence AS "factConfidence",
                   o.pattern_confidence AS "patternConfidence",o.evidence_strength AS "evidenceStrength",
                   o.confidence_breakdown AS "confidenceBreakdown",
                   o.event_time_start AS "eventTimeStart",o.event_time_end AS "eventTimeEnd",
                   o.detection_time AS "detectionTime",o.inference_time AS "inferenceTime",
                   o.knowledge_effective_time AS "knowledgeEffectiveTime",
                   o.producer,o.producer_version AS "producerVersion",o.status
            FROM behavior_pattern_occurrence o
            LEFT JOIN event_pattern_definition d ON d.pattern_code=o.pattern_code
            LEFT JOIN event_pattern_version v
              ON v.pattern_code=o.pattern_code AND v.version=o.pattern_version
            WHERE o.case_id=? AND o.status='ACTIVE'
            ORDER BY o.created_at,o.occurrence_id
            """, caseId), "eventRefs", "indicatorResultRefs",
                "matchedConstraints", "missingConstraints", "confidenceBreakdown"));
        result.put("riskEvents", normalize(jdbc.queryForList("""
            SELECT risk_event_id AS "riskEventId",risk_event_type AS "riskEventType",title,summary,
                   risk_event_type_version AS "riskEventTypeVersion",
                   risk_event_definition_ref AS "riskEventDefinitionRef",
                   definition_binding_method AS "definitionBindingMethod",
                   definition_binding_confidence AS "definitionBindingConfidence",
                   behavior_occurrence_refs AS "behaviorOccurrenceRefs",event_refs AS "eventRefs",
                   fact_confidence AS "factConfidence",risk_confidence AS "riskConfidence",
                   confidence_breakdown AS "confidenceBreakdown",
                   evidence_strength AS "evidenceStrength",detection_time AS "detectionTime",
                   inference_time AS "inferenceTime",knowledge_effective_time AS "knowledgeEffectiveTime",
                    node_maturity AS "nodeMaturity",review_decision AS "reviewDecision",
                    producer,producer_version AS "producerVersion",
                    review_status AS "reviewStatus",status
            FROM risk_event_hypothesis WHERE case_id=? AND status='ACTIVE'
            ORDER BY created_at,risk_event_id
            """, caseId), "behaviorOccurrenceRefs", "eventRefs", "confidenceBreakdown"));
        result.put("alternativeExplanations", normalize(jdbc.queryForList("""
            SELECT explanation_id AS "explanationId",alternative_type AS "alternativeType",title,summary,
                   target_risk_event_id AS "targetRiskEventId",
                   behavior_occurrence_refs AS "behaviorOccurrenceRefs",
                   supporting_evidence_refs AS "supportingEvidenceRefs",
                   contradicting_evidence_refs AS "contradictingEvidenceRefs",
                   confidence,confidence_breakdown AS "confidenceBreakdown",
                   review_status AS "reviewStatus",status
            FROM alternative_explanation
            WHERE case_id=? AND status='ACTIVE' AND alternative_type<>'DATA_QUALITY'
            ORDER BY created_at,explanation_id
            """, caseId), "behaviorOccurrenceRefs", "supportingEvidenceRefs",
                "contradictingEvidenceRefs", "confidenceBreakdown"));
        result.put("investigationHypotheses", normalize(jdbc.queryForList("""
            SELECT hypothesis_id AS "hypothesisId",risk_event_id AS "riskEventId",hypothesis,
                   evidence_needed AS "evidenceNeeded",recommended_actions AS "recommendedActions",
                   priority,status,blocking,resolution,created_at AS "createdAt",resolved_at AS "resolvedAt"
            FROM investigation_hypothesis
            WHERE case_id=? AND status IN ('OPEN','ACKNOWLEDGED')
            ORDER BY CASE status WHEN 'OPEN' THEN 0 ELSE 1 END,created_at,hypothesis_id
            """, caseId), "evidenceNeeded", "recommendedActions"));
        result.put("snapshots", normalize(jdbc.queryForList("""
            SELECT snapshot_id AS "snapshotId",revision,data_snapshot_sha256 AS "dataSnapshotSha256",
                   knowledge_release_ref AS "knowledgeReleaseRef",model_ref AS "modelRef",
                   pattern_refs AS "patternRefs",evidence_policy_version AS "evidencePolicyVersion",
                   explanation_fingerprint AS "explanationFingerprint",
                   object_counts AS "objectCounts",created_at AS "createdAt"
            FROM case_explanation_snapshot WHERE case_id=? ORDER BY revision DESC
            """, caseId), "patternRefs", "objectCounts"));
        result.put("attackPathCandidates", List.of());
        return result;
    }

    public void updateInvestigationHypothesis(String caseId, String hypothesisId,
                                              String status, String resolution) {
        if (!List.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "DISMISSED").contains(status))
            throw new IllegalArgumentException("Invalid investigation hypothesis status");
        int changed = jdbc.update("""
            UPDATE investigation_hypothesis SET status=?,resolution=?,
                resolved_at=CASE WHEN ? IN ('RESOLVED','DISMISSED') THEN CURRENT_TIMESTAMP ELSE NULL END
            WHERE case_id=? AND hypothesis_id=? AND blocking=false
            """, status, resolution, status, caseId, hypothesisId);
        if (changed == 0) throw new IllegalArgumentException("Investigation hypothesis does not exist");
    }

    public List<Map<String, Object>> matters(String caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT matter_id AS "matterId",case_id AS "caseId",matter_type AS "matterType",
                   summary,business_value AS "businessValue",certainty,event_refs AS "eventRefs",
                   evidence_refs AS "evidenceRefs",source_refs AS "sourceRefs",
                   indicator_result_refs AS "indicatorResultRefs",
                   technique_occurrence_refs AS "techniqueOccurrenceRefs",
                   behavior_occurrence_refs AS "behaviorOccurrenceRefs",
                   risk_event_refs AS "riskEventRefs",
                   relation_type AS "relationType",explanation_payload AS "explanationPayload",
                   input_snapshot_sha256 AS "inputSnapshotSha256",producer,producer_version AS "producerVersion",
                   review_feedback AS "reviewFeedback",feedback_reason AS "feedbackReason",
                   created_at AS "createdAt"
            FROM case_matter_explanation
            WHERE case_id=? AND status='ACTIVE'
            ORDER BY created_at,matter_id
            """, caseId);
        rows.forEach(row -> row.put("matterTypeName",
                MATTER_TYPE_NAMES.getOrDefault(Objects.toString(row.get("matterType"), ""), "其他事实")));
        return normalize(rows, "eventRefs", "evidenceRefs", "sourceRefs", "indicatorResultRefs",
                "techniqueOccurrenceRefs", "behaviorOccurrenceRefs", "riskEventRefs",
                "explanationPayload");
    }

    public Map<String, Object> matterEvidence(String caseId, String matterId) {
        Map<String, Object> matter = jdbc.queryForMap("""
            SELECT matter_id AS "matterId",summary,business_value AS "businessValue",certainty,
                   event_refs AS "eventRefs",evidence_refs AS "evidenceRefs",source_refs AS "sourceRefs",
                   technique_occurrence_refs AS "techniqueOccurrenceRefs",
                   explanation_payload AS "explanationPayload",producer,producer_version AS "producerVersion",
                   input_snapshot_sha256 AS "inputSnapshotSha256"
            FROM case_matter_explanation
            WHERE case_id=? AND matter_id=? AND status='ACTIVE'
            """, caseId, matterId);
        normalize(matter, "eventRefs", "evidenceRefs", "sourceRefs",
                "techniqueOccurrenceRefs", "explanationPayload");
        List<String> eventIds = jsonStrings(matter.get("eventRefs"));
        matter.put("events", eventIds.isEmpty() ? List.of() : jdbc.queryForList("""
            SELECT event_id AS "eventId",event_name AS "eventName",event_type AS "eventType",
                   event_standard_code AS "eventStandardCode",event_time AS "eventTime",confidence,
                   evidence_refs AS "evidenceRefs",event_frame_code AS "eventFrameCode",
                   event_frame_version AS "eventFrameVersion",
                   definition_binding_status AS "definitionBindingStatus",
                   definition_match_method AS "definitionMatchMethod",
                   definition_match_confidence AS "definitionMatchConfidence"
            FROM cf_risk_event
            WHERE case_id=? AND deleted=false
              AND event_id IN (
                SELECT jsonb_array_elements_text(event_refs)
                FROM case_matter_explanation
                WHERE case_id=? AND matter_id=? AND status='ACTIVE'
              )
            ORDER BY event_time NULLS LAST,event_id
            """, caseId, caseId, matterId));
        matter.put("techniques", normalize(jdbc.queryForList("""
            SELECT occurrence_id AS "occurrenceId",technique_code AS "techniqueCode",
                   technique_version AS "techniqueVersion",decision,status,explanation,event_refs AS "eventRefs",
                   producer,producer_version AS "producerVersion",input_snapshot_sha256 AS "inputSnapshotSha256"
            FROM technique_occurrence
            WHERE case_id=? AND occurrence_id IN (
                SELECT jsonb_array_elements_text(technique_occurrence_refs)
                FROM case_matter_explanation WHERE matter_id=?
            )
            ORDER BY technique_code
            """, caseId, matterId), "eventRefs"));
        return matter;
    }

    public List<Map<String, Object>> techniques(String caseId) {
        List<Map<String, Object>> rows = normalize(jdbc.queryForList("""
            SELECT occurrence_id AS "occurrenceId",technique_code AS "techniqueCode",
                   technique_version AS "techniqueVersion",tactic_codes AS "tacticCodes",
                   subject_refs AS "subjectRefs",event_refs AS "eventRefs",
                   behavior_occurrence_refs AS "behaviorOccurrenceRefs",
                   indicator_result_refs AS "indicatorResultRefs",decision,status,
                   risk_event_refs AS "riskEventRefs",
                   fact_confidence AS "factConfidence",
                   mapping_confidence AS "mappingConfidence",
                   risk_confidence AS "riskConfidence",
                   confidence_breakdown AS "confidenceBreakdown",
                   evidence_strength AS "evidenceStrength",
                   knowledge_authority_level AS "knowledgeAuthorityLevel",
                   review_status AS "reviewStatus",
                   explanation,producer,producer_version AS "producerVersion",
                   input_snapshot_sha256 AS "inputSnapshotSha256",created_at AS "createdAt"
            FROM technique_occurrence WHERE case_id=? AND status<>'SUPERSEDED'
            ORDER BY technique_code,created_at DESC
            """, caseId), "tacticCodes", "subjectRefs", "eventRefs",
                "behaviorOccurrenceRefs", "indicatorResultRefs", "riskEventRefs",
                "confidenceBreakdown");
        enrichPersistedTechniqueNarratives(caseId, rows);
        return rows;
    }

    public List<Map<String, Object>> suggestions(String caseId) {
        return jdbc.queryForList("""
            SELECT suggestion_id AS "suggestionId",target_type AS "targetType",target_id AS "targetId",
                   topic,reason,expected_material AS "expectedMaterial",priority,status,blocking,
                   resolution,created_at AS "createdAt",resolved_at AS "resolvedAt"
            FROM case_review_suggestion
            WHERE case_id=? AND status IN ('OPEN','ACKNOWLEDGED')
            ORDER BY CASE status WHEN 'OPEN' THEN 0 ELSE 1 END,created_at,suggestion_id
            """, caseId);
    }

    private void enrichPersistedTechniqueNarratives(
            String caseId, List<Map<String, Object>> techniques) {
        if (techniques.isEmpty()) return;
        Map<String, String> eventNames = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT event_id,event_name FROM cf_risk_event
                WHERE case_id=? AND deleted=false
                """, caseId).forEach(row -> eventNames.put(
                Objects.toString(row.get("event_id")),
                Objects.toString(row.get("event_name"), "未命名事件")));
        Map<String, String> indicatorNames = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT r.calculation_id,d.indicator_name
                FROM indicator_calculation_result r
                JOIN indicator_definition d ON d.indicator_code=r.indicator_code
                WHERE r.subject_id=?
                ORDER BY r.calculated_at DESC
                """, caseId).forEach(row -> indicatorNames.putIfAbsent(
                Objects.toString(row.get("calculation_id")),
                Objects.toString(row.get("indicator_name"))));
        Map<String, String> patternNames = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT o.occurrence_id,COALESCE(d.pattern_name,o.pattern_code) AS pattern_name
                FROM behavior_pattern_occurrence o
                LEFT JOIN event_pattern_definition d ON d.pattern_code=o.pattern_code
                WHERE o.case_id=?
                ORDER BY o.created_at DESC
                """, caseId).forEach(row -> patternNames.putIfAbsent(
                Objects.toString(row.get("occurrence_id")),
                Objects.toString(row.get("pattern_name"))));
        Map<String, String> techniqueNames = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT term_code,term_name FROM ontology_term
                WHERE namespace='AMLTRIX_TECHNIQUE' AND status='ACTIVE'
                ORDER BY term_version DESC
                """).forEach(row -> techniqueNames.putIfAbsent(
                Objects.toString(row.get("term_code")),
                Objects.toString(row.get("term_name"))));
        for (Map<String, Object> technique : techniques) {
            String code = Objects.toString(technique.get("techniqueCode"), "");
            String name = techniqueNames.getOrDefault(code, code);
            List<String> events = namesFromValues(
                    technique.get("eventRefs"), eventNames, 3);
            List<String> indicators = namesFromValues(
                    technique.get("indicatorResultRefs"), indicatorNames, 3);
            List<String> patterns = namesFromValues(
                    technique.get("behaviorOccurrenceRefs"), patternNames, 2);
            String observed = events.isEmpty() ? "本案标准化事件" : quoteNames(events);
            String indicatorText = indicators.isEmpty() ? "相关正式指标" : quoteNames(indicators);
            String patternText = patterns.isEmpty()
                    ? "" : "，并形成" + quoteNames(patterns) + "行为组合";
            technique.put("techniqueName", name);
            technique.put("explanation",
                    observed + patternText + "，命中" + indicatorText
                            + "；这些可观察特征与 AMLTRIX " + code + "“" + name
                            + "”的技术特征一致，因此形成技术候选。"
                            + "该映射说明行为特征相符，不单独证明主观目的；"
                            + "若原始流水、主体关系或业务凭证不能支持上述事件之间的关联，"
                            + "应降低或排除该技术判断。");
        }
    }

    private List<String> namesFromValues(Object refs, Map<String, String> names, int limit) {
        List<String> output = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (refs instanceof JsonNode node && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        } else if (refs instanceof List<?> list) {
            list.forEach(item -> values.add(Objects.toString(item)));
        }
        for (String ref : values) {
            String name = names.get(ref);
            if (name != null && !name.isBlank() && !output.contains(name)) output.add(name);
            if (output.size() >= limit) break;
        }
        return output;
    }

    public void feedback(String caseId, String matterId, String feedback, String reason) {
        if (!List.of("HELPFUL", "NOT_HELPFUL", "UNCERTAIN").contains(feedback))
            throw new IllegalArgumentException("feedback must be HELPFUL, NOT_HELPFUL or UNCERTAIN");
        int changed = jdbc.update("""
            UPDATE case_matter_explanation
            SET review_feedback=?,feedback_reason=?,updated_at=CURRENT_TIMESTAMP
            WHERE case_id=? AND matter_id=? AND status='ACTIVE'
            """, feedback, reason, caseId, matterId);
        if (changed == 0) throw new IllegalArgumentException("Matter explanation does not exist");
    }

    public void updateSuggestion(String caseId, String suggestionId, String status, String resolution) {
        if (!List.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "DISMISSED").contains(status))
            throw new IllegalArgumentException("Invalid suggestion status");
        int changed = jdbc.update("""
            UPDATE case_review_suggestion SET status=?,resolution=?,
                resolved_at=CASE WHEN ? IN ('RESOLVED','DISMISSED') THEN CURRENT_TIMESTAMP ELSE NULL END
            WHERE case_id=? AND suggestion_id=? AND blocking=false
            """, status, resolution, status, caseId, suggestionId);
        if (changed == 0) throw new IllegalArgumentException("Review suggestion does not exist");
    }

    private List<Map<String, Object>> events(String caseId) {
        return jdbc.queryForList("""
            SELECT event_id AS "eventId",event_name AS "eventName",event_type AS "eventType",
                   event_standard_code AS "eventStandardCode",event_time AS "occurredAt",confidence,
                   evidence_refs AS "evidenceRefs",event_frame_code AS "eventFrameCode",
                   event_frame_version AS "eventFrameVersion",
                   definition_binding_status AS "definitionBindingStatus"
            FROM cf_risk_event WHERE case_id=? AND deleted=false
            ORDER BY event_time NULLS LAST,event_id
            """, caseId);
    }

    private List<Map<String, Object>> transactions(String caseId) {
        return jdbc.queryForList("""
            WITH member_tx AS (
              SELECT DISTINCT t.*
              FROM case_signal_rel csr
              JOIN risk_signal s ON s.signal_id=csr.signal_id
              JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
              WHERE csr.case_id=?
            ), context_ids AS (
              SELECT DISTINCT id
              FROM (
                (SELECT t.id FROM member_tx m
                 JOIN risk_transaction_materialized t
                   ON t.batch_id=m.batch_id AND t.account_hash=m.account_hash
                  AND t.occurred_at BETWEEN m.occurred_at-INTERVAL '10 minutes'
                                        AND m.occurred_at+INTERVAL '10 minutes'
                 LIMIT 500)
                UNION ALL
                (SELECT t.id FROM member_tx m
                 JOIN risk_transaction_materialized t
                   ON t.batch_id=m.batch_id AND t.account_hash=m.counterparty_hash
                  AND t.occurred_at BETWEEN m.occurred_at-INTERVAL '10 minutes'
                                        AND m.occurred_at+INTERVAL '10 minutes'
                 WHERE m.counterparty_hash IS NOT NULL
                 LIMIT 500)
                UNION ALL
                (SELECT t.id FROM member_tx m
                 JOIN risk_transaction_materialized t
                   ON t.batch_id=m.batch_id AND t.counterparty_hash=m.account_hash
                  AND t.occurred_at BETWEEN m.occurred_at-INTERVAL '10 minutes'
                                        AND m.occurred_at+INTERVAL '10 minutes'
                 WHERE t.counterparty_hash IS NOT NULL
                 LIMIT 500)
                UNION ALL
                (SELECT t.id FROM member_tx m
                 JOIN risk_transaction_materialized t
                   ON t.batch_id=m.batch_id AND t.counterparty_hash=m.counterparty_hash
                  AND t.occurred_at BETWEEN m.occurred_at-INTERVAL '10 minutes'
                                        AND m.occurred_at+INTERVAL '10 minutes'
                 WHERE t.counterparty_hash IS NOT NULL
                   AND m.counterparty_hash IS NOT NULL
                 LIMIT 500)
              ) candidates
              LIMIT 500
            )
            SELECT t.source_record_id AS "sourceRecordId",t.id AS "sourceLine",
                   (m.id IS NOT NULL) AS "caseMember",t.occurred_at AS timestamp,
                   t.account_hash AS "sourceAccount",t.counterparty_hash AS "targetAccount",
                   t.amount,t.currency,t.transaction_type AS "paymentFormat",
                   t.record_sha256 AS "recordSha256"
            FROM context_ids c
            JOIN risk_transaction_materialized t ON t.id=c.id
            LEFT JOIN member_tx m ON m.id=t.id
            ORDER BY t.occurred_at,t.id
            """, caseId);
    }

    @Transactional
    protected void persist(Map<String, Object> riskCase, JsonNode result) {
        String caseId = Objects.toString(riskCase.get("case_id"));
        String bankCode = Objects.toString(riskCase.get("bank_code"));
        long workspaceId = riskCase.get("workspace_id") instanceof Number number ? number.longValue() : 1L;
        String snapshot = result.path("inputSnapshotSha256").asText();
        String producer = result.path("algorithm").path("id").asText("AMLTRIX_CASE_MATTER_BASELINE");
        String producerVersion = result.path("algorithm").path("version").asText("1.0");
        jdbc.update("UPDATE case_matter_explanation SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status='ACTIVE'", caseId);
        jdbc.update("UPDATE technique_occurrence SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status<>'SUPERSEDED'", caseId);
        jdbc.update("UPDATE behavior_pattern_occurrence SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status='ACTIVE'", caseId);
        jdbc.update("UPDATE risk_event_hypothesis SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status='ACTIVE'", caseId);
        jdbc.update("UPDATE alternative_explanation SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status='ACTIVE'", caseId);
        jdbc.update("""
            UPDATE investigation_hypothesis
            SET status='DISMISSED',
                resolution='核心链刷新后由新的调查假设取代',
                resolved_at=CURRENT_TIMESTAMP
            WHERE case_id=? AND status IN ('OPEN','ACKNOWLEDGED')
            """, caseId);
        jdbc.update("""
            UPDATE case_review_suggestion
            SET status='DISMISSED',
                resolution='案例分析刷新后由新的关注建议取代',
                resolved_at=CURRENT_TIMESTAMP
            WHERE case_id=? AND status IN ('OPEN','ACKNOWLEDGED')
            """, caseId);

        for (JsonNode occurrence : result.path("behaviorPatternOccurrences")) {
            jdbc.update("""
                INSERT INTO behavior_pattern_occurrence
                  (occurrence_id,bank_code,workspace_id,case_id,pattern_code,pattern_version,
                   pattern_class,event_refs,indicator_result_refs,matched_constraints,missing_constraints,
                   fact_confidence,pattern_confidence,confidence_breakdown,evidence_strength,
                   event_time_start,event_time_end,
                   producer,producer_version,input_snapshot_sha256,status)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb,?,?::timestamptz,
                        ?::timestamptz,?,?,?,?)
                ON CONFLICT (occurrence_id) DO UPDATE SET
                  pattern_code=EXCLUDED.pattern_code,
                  pattern_version=EXCLUDED.pattern_version,
                  pattern_class=EXCLUDED.pattern_class,
                  event_refs=EXCLUDED.event_refs,
                  indicator_result_refs=EXCLUDED.indicator_result_refs,
                  matched_constraints=EXCLUDED.matched_constraints,
                  missing_constraints=EXCLUDED.missing_constraints,
                  fact_confidence=EXCLUDED.fact_confidence,
                  pattern_confidence=EXCLUDED.pattern_confidence,
                  confidence_breakdown=EXCLUDED.confidence_breakdown,
                  evidence_strength=EXCLUDED.evidence_strength,
                  event_time_start=EXCLUDED.event_time_start,
                  event_time_end=EXCLUDED.event_time_end,
                  detection_time=CURRENT_TIMESTAMP,
                  inference_time=CURRENT_TIMESTAMP,
                  producer=EXCLUDED.producer,
                  producer_version=EXCLUDED.producer_version,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256,
                  status='ACTIVE',
                  updated_at=CURRENT_TIMESTAMP
                """, occurrence.path("occurrenceId").asText(), bankCode, workspaceId, caseId,
                    occurrence.path("patternCode").asText(),
                    occurrence.path("patternVersion").asText("1.0"),
                    occurrence.path("patternClass").asText("RISK"),
                    json(occurrence.path("eventRefs")),
                    json(occurrence.path("indicatorResultRefs")),
                    json(occurrence.path("matchedConstraints")),
                    json(occurrence.path("missingConstraints")),
                    decimalOrNull(occurrence, "factConfidence"),
                    decimalOrNull(occurrence, "patternConfidence"),
                    json(occurrence.path("confidenceBreakdown")),
                    occurrence.path("evidenceStrength").asText("E1"),
                    textOrNull(occurrence, "eventTimeStart"), textOrNull(occurrence, "eventTimeEnd"),
                    producer, producerVersion, snapshot, occurrence.path("status").asText("ACTIVE"));
        }

        for (JsonNode risk : result.path("riskEvents")) {
            Map<String, Object> riskDefinition = jdbc.query("""
                SELECT risk_event_type,version
                FROM risk_event_type_definition
                WHERE risk_event_type=? AND status='ACTIVE'
                ORDER BY version DESC LIMIT 1
                """, rs -> rs.next() ? Map.of(
                    "type", rs.getString("risk_event_type"),
                    "version", rs.getString("version")) : Map.of(),
                    risk.path("riskEventType").asText());
            String riskTypeVersion = Objects.toString(riskDefinition.get("version"), "");
            String riskDefinitionRef = riskTypeVersion.isBlank() ? null
                    : "RISK_EVENT_TYPE:" + risk.path("riskEventType").asText() + ":v" + riskTypeVersion;
            String bindingMethod = riskTypeVersion.isBlank() ? "UNRESOLVED" : "EXACT_CODE";
            String nodeMaturity = riskTypeVersion.isBlank() ? "CANDIDATE" : "SUPPORTED";
            jdbc.update("""
                INSERT INTO risk_event_hypothesis
                  (risk_event_id,bank_code,workspace_id,case_id,risk_event_type,title,summary,
                   behavior_occurrence_refs,event_refs,fact_confidence,risk_confidence,
                   confidence_breakdown,evidence_strength,producer,producer_version,input_snapshot_sha256,
                   review_status,status,risk_event_type_version,risk_event_definition_ref,
                   definition_binding_method,definition_binding_confidence,node_maturity)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (risk_event_id) DO UPDATE SET
                  summary=EXCLUDED.summary,behavior_occurrence_refs=EXCLUDED.behavior_occurrence_refs,
                  event_refs=EXCLUDED.event_refs,fact_confidence=EXCLUDED.fact_confidence,
                  risk_confidence=EXCLUDED.risk_confidence,
                  confidence_breakdown=EXCLUDED.confidence_breakdown,
                  risk_event_type_version=EXCLUDED.risk_event_type_version,
                  risk_event_definition_ref=EXCLUDED.risk_event_definition_ref,
                  definition_binding_method=EXCLUDED.definition_binding_method,
                  definition_binding_confidence=EXCLUDED.definition_binding_confidence,
                  node_maturity=EXCLUDED.node_maturity,
                  evidence_strength=EXCLUDED.evidence_strength,status='ACTIVE',
                  updated_at=CURRENT_TIMESTAMP
                """, risk.path("riskEventId").asText(), bankCode, workspaceId, caseId,
                    risk.path("riskEventType").asText(), risk.path("title").asText(),
                    risk.path("summary").asText(), json(risk.path("behaviorOccurrenceRefs")),
                    json(risk.path("eventRefs")), decimalOrNull(risk, "factConfidence"),
                    risk.path("riskConfidence").asDouble(0),
                    json(risk.path("confidenceBreakdown")),
                    risk.path("evidenceStrength").asText("E1"), producer, producerVersion, snapshot,
                    risk.path("reviewStatus").asText("PENDING"), risk.path("status").asText("ACTIVE"),
                    riskTypeVersion.isBlank() ? null : riskTypeVersion, riskDefinitionRef,
                    bindingMethod, riskTypeVersion.isBlank() ? null : 1.0, nodeMaturity);
        }

        for (JsonNode alternative : result.path("alternativeExplanations")) {
            jdbc.update("""
                INSERT INTO alternative_explanation
                  (explanation_id,bank_code,workspace_id,case_id,alternative_type,title,summary,
                   target_risk_event_id,behavior_occurrence_refs,supporting_evidence_refs,
                   contradicting_evidence_refs,confidence,confidence_breakdown,status,review_status,producer,
                   producer_version,input_snapshot_sha256)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?,?,?,?)
                ON CONFLICT (explanation_id) DO UPDATE SET
                  summary=EXCLUDED.summary,supporting_evidence_refs=EXCLUDED.supporting_evidence_refs,
                  contradicting_evidence_refs=EXCLUDED.contradicting_evidence_refs,
                  confidence=EXCLUDED.confidence,
                  confidence_breakdown=EXCLUDED.confidence_breakdown,
                  status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                """, alternative.path("explanationId").asText(), bankCode, workspaceId, caseId,
                    alternative.path("alternativeType").asText(), alternative.path("title").asText(),
                    alternative.path("summary").asText(),
                    textOrNull(alternative, "targetRiskEventId"),
                    json(alternative.path("behaviorOccurrenceRefs")),
                    json(alternative.path("supportingEvidenceRefs")),
                    json(alternative.path("contradictingEvidenceRefs")),
                    decimalOrNull(alternative, "confidence"),
                    json(alternative.path("confidenceBreakdown")),
                    alternative.path("status").asText("ACTIVE"),
                    alternative.path("reviewStatus").asText("PENDING"),
                    producer, producerVersion, snapshot);
        }

        for (JsonNode hypothesis : result.path("investigationHypotheses")) {
            jdbc.update("""
                INSERT INTO investigation_hypothesis
                  (hypothesis_id,bank_code,workspace_id,case_id,risk_event_id,hypothesis,
                   evidence_needed,recommended_actions,priority,status,blocking,producer,
                   producer_version,input_snapshot_sha256)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,false,?,?,?)
                ON CONFLICT (hypothesis_id) DO UPDATE SET
                  risk_event_id=EXCLUDED.risk_event_id,
                  hypothesis=EXCLUDED.hypothesis,
                  evidence_needed=EXCLUDED.evidence_needed,
                  recommended_actions=EXCLUDED.recommended_actions,
                  priority=EXCLUDED.priority,
                  status=EXCLUDED.status,
                  resolution=NULL,
                  resolved_at=NULL,
                  producer=EXCLUDED.producer,
                  producer_version=EXCLUDED.producer_version,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256
                """, hypothesis.path("hypothesisId").asText(), bankCode, workspaceId, caseId,
                    textOrNull(hypothesis, "riskEventId"), hypothesis.path("hypothesis").asText(),
                    json(hypothesis.path("evidenceNeeded")),
                    json(hypothesis.path("recommendedActions")),
                    hypothesis.path("priority").asText("MEDIUM"),
                    hypothesis.path("status").asText("OPEN"),
                    producer, producerVersion, snapshot);
        }

        Map<String, String> occurrenceByCode = new LinkedHashMap<>();
        for (JsonNode occurrence : result.path("techniqueOccurrences")) {
            String occurrenceId = occurrence.path("occurrenceId").asText();
            String code = occurrence.path("techniqueCode").asText();
            occurrenceByCode.put(code, occurrenceId);
            String eventRefs = json(occurrence.path("eventRefs"));
            jdbc.update("""
                INSERT INTO technique_occurrence
                  (occurrence_id,bank_code,workspace_id,case_id,technique_code,technique_version,
                   event_refs,behavior_occurrence_refs,indicator_result_refs,risk_event_refs,
                   decision,status,review_status,explanation,
                   fact_confidence,mapping_confidence,risk_confidence,confidence_breakdown,evidence_strength,
                   knowledge_authority_level,node_maturity,dedup_key,producer,producer_version,input_snapshot_sha256)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?, 'PENDING',?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                ON CONFLICT (occurrence_id) DO UPDATE SET
                  technique_version=EXCLUDED.technique_version,
                  event_refs=EXCLUDED.event_refs,
                   behavior_occurrence_refs=EXCLUDED.behavior_occurrence_refs,
                   indicator_result_refs=EXCLUDED.indicator_result_refs,
                   risk_event_refs=EXCLUDED.risk_event_refs,
                   decision=EXCLUDED.decision,status=EXCLUDED.status,
                  explanation=EXCLUDED.explanation,producer_version=EXCLUDED.producer_version,
                  fact_confidence=EXCLUDED.fact_confidence,
                  mapping_confidence=EXCLUDED.mapping_confidence,
                  risk_confidence=EXCLUDED.risk_confidence,
                  confidence_breakdown=EXCLUDED.confidence_breakdown,
                  evidence_strength=EXCLUDED.evidence_strength,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256,updated_at=CURRENT_TIMESTAMP
                """, occurrenceId, bankCode, workspaceId, caseId, code,
                    occurrence.path("techniqueVersion").asText("1.0"), eventRefs,
                    json(occurrence.path("behaviorOccurrenceRefs")),
                    json(occurrence.path("indicatorResultRefs")),
                    json(occurrence.path("riskEventRefs")),
                    occurrence.path("decision").asText("CANDIDATE"),
                    occurrence.path("status").asText("CANDIDATE"),
                    occurrence.path("explanation").asText(),
                    decimalOrNull(occurrence, "factConfidence"),
                    decimalOrNull(occurrence, "mappingConfidence"),
                    decimalOrNull(occurrence, "riskConfidence"),
                    json(occurrence.path("confidenceBreakdown")),
                    occurrence.path("evidenceStrength").asText("E1"),
                    occurrence.path("knowledgeAuthorityLevel").asText("K4"),
                    "ACTIVE".equals(occurrence.path("status").asText())
                            ? "SUPPORTED" : "CANDIDATE",
                    sha256(caseId + "|" + code + "|" + snapshot), producer, producerVersion, snapshot);
            for (JsonNode eventRef : occurrence.path("eventRefs")) {
                jdbc.update("""
                    INSERT INTO technique_occurrence_event_rel(occurrence_id,event_id,role,evidence_ref)
                    SELECT ?,?,'SUPPORT',?::jsonb
                    WHERE EXISTS (SELECT 1 FROM cf_risk_event WHERE event_id=? AND case_id=?)
                    ON CONFLICT DO NOTHING
                    """, occurrenceId, eventRef.asText(), json(occurrence.path("sourceRefs")),
                        eventRef.asText(), caseId);
            }
        }

        for (JsonNode matter : result.path("matters")) {
            ArrayNode occurrenceRefs = mapper.createArrayNode();
            for (JsonNode code : matter.path("techniqueCodes")) {
                String occurrenceId = occurrenceByCode.get(code.asText());
                if (occurrenceId != null) occurrenceRefs.add(occurrenceId);
            }
            String matterId = matter.path("matterId").asText();
            String content = json(matter);
            jdbc.update("""
                INSERT INTO case_matter_explanation
                  (matter_id,bank_code,workspace_id,case_id,matter_type,summary,business_value,certainty,
                   event_refs,evidence_refs,source_refs,indicator_result_refs,technique_occurrence_refs,
                   behavior_occurrence_refs,risk_event_refs,relation_type,explanation_payload,
                   input_snapshot_sha256,producer,producer_version,content_sha256,dedup_key,status)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,
                        ?::jsonb,?::jsonb,?,?::jsonb,?,?,?,?,?,'ACTIVE')
                ON CONFLICT (matter_id) DO UPDATE SET
                  summary=EXCLUDED.summary,business_value=EXCLUDED.business_value,
                  certainty=EXCLUDED.certainty,event_refs=EXCLUDED.event_refs,
                  evidence_refs=EXCLUDED.evidence_refs,source_refs=EXCLUDED.source_refs,
                  indicator_result_refs=EXCLUDED.indicator_result_refs,
                  technique_occurrence_refs=EXCLUDED.technique_occurrence_refs,
                  behavior_occurrence_refs=EXCLUDED.behavior_occurrence_refs,
                  risk_event_refs=EXCLUDED.risk_event_refs,
                  relation_type=EXCLUDED.relation_type,
                  explanation_payload=EXCLUDED.explanation_payload,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256,
                  producer=EXCLUDED.producer,producer_version=EXCLUDED.producer_version,
                  content_sha256=EXCLUDED.content_sha256,dedup_key=EXCLUDED.dedup_key,
                  status='ACTIVE',
                  updated_at=CURRENT_TIMESTAMP
                """, matterId, bankCode, workspaceId, caseId,
                    matter.path("matterType").asText(), matter.path("summary").asText(),
                    matter.path("businessValue").asText(), matter.path("certainty").asText(),
                    json(matter.path("eventRefs")), json(matter.path("evidenceRefs")),
                    json(matter.path("sourceRefs")), json(matter.path("indicatorResultRefs")),
                    json(occurrenceRefs), json(matter.path("behaviorOccurrenceRefs")),
                    json(matter.path("riskEventRefs")), matter.path("relationType").asText(null),
                    json(matter.path("details")), snapshot, producer, producerVersion,
                    sha256(content), sha256(caseId + "|" + matterId + "|" + snapshot));
        }

        for (JsonNode suggestion : result.path("reviewSuggestions")) {
            jdbc.update("""
                INSERT INTO case_review_suggestion
                  (suggestion_id,bank_code,workspace_id,case_id,target_type,target_id,topic,reason,
                   expected_material,priority,status,blocking)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,false)
                ON CONFLICT (suggestion_id) DO UPDATE SET
                  topic=EXCLUDED.topic,reason=EXCLUDED.reason,
                  expected_material=EXCLUDED.expected_material,
                  priority=EXCLUDED.priority,status=EXCLUDED.status,
                  resolution=NULL,resolved_at=NULL
                """, suggestion.path("suggestionId").asText(), bankCode, workspaceId, caseId,
                    suggestion.path("targetType").asText("CASE"),
                    suggestion.path("targetId").asText(caseId), suggestion.path("topic").asText(),
                    suggestion.path("reason").asText(), suggestion.path("expectedMaterial").asText(null),
                    suggestion.path("priority").asText("MEDIUM"),
                    suggestion.path("status").asText("OPEN"));
        }

        List<String> patternRefs = new ArrayList<>();
        result.path("behaviorPatternOccurrences").forEach(item ->
                patternRefs.add(item.path("patternCode").asText() + "@" +
                        item.path("patternVersion").asText("1.0")));
        String knowledgeRelease = "AMLTRIX@K4";
        String modelRef = producer + "@" + producerVersion;
        String evidencePolicy = "EVIDENCE_STRENGTH/1.0";
        String fingerprint = sha256(snapshot + "|" + knowledgeRelease + "|" + modelRef + "|"
                + String.join(",", patternRefs) + "|" + evidencePolicy);
        Map<String, Object> counts = Map.of(
                "behaviorPatterns", result.path("behaviorPatternOccurrences").size(),
                "riskEvents", result.path("riskEvents").size(),
                "alternatives", result.path("alternativeExplanations").size(),
                "techniques", result.path("techniqueOccurrences").size(),
                "matters", result.path("matters").size(),
                "attackPathCandidates", 0);
        jdbc.update("""
            INSERT INTO case_explanation_snapshot
              (snapshot_id,case_id,revision,data_snapshot_sha256,knowledge_release_ref,
               model_ref,pattern_refs,evidence_policy_version,explanation_fingerprint,object_counts)
            SELECT ?,?,COALESCE((SELECT MAX(revision)+1 FROM case_explanation_snapshot WHERE case_id=?),1),
                   ?,?,?,?::jsonb,?,?,?::jsonb
            WHERE NOT EXISTS (
              SELECT 1 FROM case_explanation_snapshot
              WHERE case_id=? AND explanation_fingerprint=?
            )
            """, "EXPL-" + fingerprint.substring(0, 24).toUpperCase(), caseId, caseId,
                snapshot, knowledgeRelease, modelRef, json(patternRefs), evidencePolicy,
                fingerprint, json(counts), caseId, fingerprint);
        jdbc.update("""
            UPDATE cf_risk_case
            SET explanation_status='EXPLAINED',explanation_fingerprint=?,
                explanation_updated_at=CURRENT_TIMESTAMP
            WHERE case_id=?
            """, fingerprint, caseId);
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows, String... fields) {
        rows.forEach(row -> normalize(row, fields));
        return rows;
    }

    private void normalize(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            Object value = row.get(field);
            if (value == null || value instanceof JsonNode || value instanceof Map || value instanceof List) continue;
            try {
                if (value instanceof org.postgresql.util.PGobject pg) row.put(field, mapper.readTree(pg.getValue()));
                else row.put(field, mapper.readTree(Objects.toString(value)));
            } catch (Exception ignored) {
                // Preserve the original database value for diagnostics.
            }
        }
    }

    private List<String> jsonStrings(Object value) {
        JsonNode node;
        if (value instanceof JsonNode jsonNode) node = jsonNode;
        else {
            try { node = mapper.readTree(Objects.toString(value, "[]")); }
            catch (Exception ex) { return List.of(); }
        }
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> result.add(item.asText()));
        return result;
    }

    private String json(Object value) {
        try {
            if (value instanceof JsonNode node) return mapper.writeValueAsString(node);
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON value", ex);
        }
    }

    private Double decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(Objects.toString(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int size(Object value) {
        if (value instanceof List<?> list) return list.size();
        if (value instanceof JsonNode node && node.isArray()) return node.size();
        return 0;
    }

    private List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) values.forEach(value -> {
            String text = value.asText("");
            if (!text.isBlank()) result.add(text);
        });
        return result;
    }

    private double averageRefs(List<String> refs, Map<String, Double> values,
                               double fallback) {
        return refs.stream().filter(values::containsKey)
                .mapToDouble(values::get).average().orElse(fallback);
    }

    private double averageNodes(List<JsonNode> nodes, String field,
                                double fallback) {
        return nodes.stream().filter(node -> node.path(field).isNumber())
                .mapToDouble(node -> node.path(field).asDouble())
                .average().orElse(fallback);
    }

    private double constraintCoverage(JsonNode pattern) {
        int matched = pattern.path("matchedConstraints").size();
        int missing = pattern.path("missingConstraints").size();
        return matched + missing == 0 ? 0.55
                : matched / (double) (matched + missing);
    }

    private double weighted(Object... valueWeights) {
        double sum = 0;
        double weights = 0;
        for (int index = 0; index + 1 < valueWeights.length; index += 2) {
            double value = ((Number) valueWeights[index]).doubleValue();
            double weight = ((Number) valueWeights[index + 1]).doubleValue();
            sum += bounded(value) * weight;
            weights += weight;
        }
        return bounded(weights == 0 ? 0 : sum / weights);
    }

    private double bounded(double value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 1_000_000d)
                / 1_000_000d;
    }

    private Map<String, Object> factor(String name, double value,
                                       double weight) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("value", bounded(value));
        result.put("weight", weight);
        result.put("weightedValue", bounded(value * weight));
        return result;
    }

    private JsonNode scoreBreakdown(Object... values) {
        ObjectNode result = mapper.createObjectNode();
        result.put("method", "WEIGHTED_EVIDENCE_V1");
        ArrayNode scores = result.putArray("scores");
        ObjectNode current = null;
        for (int index = 0; index < values.length;) {
            if (values[index] instanceof String name
                    && index + 1 < values.length
                    && values[index + 1] instanceof Number score) {
                current = scores.addObject();
                current.put("name", name);
                current.put("score", bounded(score.doubleValue()));
                current.putArray("factors");
                index += 2;
            } else if (values[index] instanceof Map<?, ?> factor
                    && current != null) {
                current.withArray("factors").add(mapper.valueToTree(factor));
                index++;
            } else {
                index++;
            }
        }
        return result;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
