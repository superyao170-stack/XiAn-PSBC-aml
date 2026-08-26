package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class IndicatorExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IndicatorExpressionEvaluator evaluator;

    public IndicatorExecutionService(JdbcTemplate jdbc, ObjectMapper mapper,
                                     IndicatorExpressionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.evaluator = evaluator;
    }

    @Transactional
    public List<Map<String, Object>> execute(String bankCode, String scenarioCode,
                                             String subjectType, String subjectId,
                                             List<String> requestedIndicators,
                                             Map<String, Object> features) {
        List<Map<String, Object>> definitions = requestedIndicators == null || requestedIndicators.isEmpty()
                ? jdbc.queryForList("""
                    SELECT v.indicator_code,v.version,v.expression::text AS expression,
                           v.threshold_config::text AS threshold_config,v.algorithm_id,v.algorithm_version
                    FROM scenario_indicator_binding b
                    JOIN indicator_version v ON v.indicator_code=b.indicator_code AND v.version=b.indicator_version
                    WHERE b.bank_code=? AND b.scenario_code=? AND b.status='ACTIVE'
                      AND b.effective_from<=CURRENT_TIMESTAMP
                      AND (b.effective_to IS NULL OR b.effective_to>CURRENT_TIMESTAMP)
                      AND v.status='ACTIVE'
                    ORDER BY b.id
                    """, bankCode, scenarioCode)
                : queryRequested(requestedIndicators);
        if (definitions.isEmpty()) throw new IllegalStateException("No active indicators were resolved");
        String inputHash = sha256(json(features));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> definition : definitions) {
            try {
                String code = Objects.toString(definition.get("indicator_code"));
                int version = ((Number) definition.get("version")).intValue();
                JsonNode expression = mapper.readTree(Objects.toString(definition.get("expression"), "{}"));
                JsonNode thresholds = mapper.readTree(Objects.toString(definition.get("threshold_config"), "{}"));
                IndicatorExpressionEvaluator.Evaluation evaluation = evaluator.evaluate(expression, features);
                String riskLevel = riskLevel(evaluation.value(), thresholds);
                String calculationId = "ICALC-" + UUID.randomUUID();
                Map<String, Object> explanation = new LinkedHashMap<>();
                explanation.put("expression", expression);
                explanation.put("contributions", evaluation.contributions());
                explanation.put("thresholds", thresholds);
                attachLineage(explanation, evaluation.contributions(), features);
                jdbc.update("""
                    INSERT INTO indicator_calculation_result
                      (calculation_id,bank_code,scenario_code,subject_type,subject_id,
                       indicator_code,indicator_version,numeric_value,risk_level,explanation,
                       input_snapshot_sha256,algorithm_execution_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,NULL)
                    """, calculationId, bankCode, scenarioCode, subjectType, subjectId,
                        code, version, evaluation.value(), riskLevel, json(explanation), inputHash);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("calculationId", calculationId);
                result.put("indicatorCode", code);
                result.put("indicatorVersion", version);
                result.put("value", evaluation.value());
                result.put("riskLevel", riskLevel);
                result.put("inputSnapshotSha256", inputHash);
                result.put("explanation", explanation);
                results.add(result);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Indicator calculation failed: " + ex.getMessage(), ex);
            }
        }
        return results;
    }

    private void attachLineage(Map<String, Object> explanation,
                               List<Map<String, Object>> contributions,
                               Map<String, Object> features) {
        Object raw = features.get("_provenance");
        if (!(raw instanceof Map<?, ?> provenance)) return;
        Set<String> paths = new LinkedHashSet<>();
        Set<String> eventRefs = new LinkedHashSet<>();
        Set<String> transactionRefs = new LinkedHashSet<>();
        List<Object> evidenceRefs = new ArrayList<>();
        Set<String> evidenceKeys = new LinkedHashSet<>();
        for (Map<String, Object> contribution : contributions) {
            String path = Objects.toString(contribution.get("path"), "");
            if (path.isBlank()) continue;
            paths.add(path);
            Object value = provenance.get(path);
            if (!(value instanceof Map<?, ?> lineage)) continue;
            addStrings(eventRefs, lineage.get("eventRefs"));
            addStrings(transactionRefs, lineage.get("transactionRefs"));
            addObjects(evidenceRefs, evidenceKeys, lineage.get("evidenceRefs"));
        }
        explanation.put("lineagePaths", new ArrayList<>(paths));
        explanation.put("eventRefs", new ArrayList<>(eventRefs));
        explanation.put("transactionRefs", new ArrayList<>(transactionRefs));
        explanation.put("evidenceRefs", evidenceRefs);
    }

    private void addStrings(Set<String> target, Object raw) {
        if (!(raw instanceof Iterable<?> values)) return;
        for (Object value : values) {
            String text = Objects.toString(value, "");
            if (!text.isBlank()) target.add(text);
        }
    }

    private void addObjects(List<Object> target, Set<String> keys, Object raw) {
        if (!(raw instanceof Iterable<?> values)) return;
        for (Object value : values) {
            String key = json(value);
            if (keys.add(key)) target.add(value);
        }
    }

    /**
     * Persists text indicators extracted by the analytics worker. These are
     * reported observations with exact source spans, not transaction-derived
     * facts. The active indicator definition remains the authority for version
     * and thresholds; arbitrary worker codes are rejected.
     */
    @Transactional
    public List<Map<String, Object>> persistWorkerObservations(
            String bankCode, String scenarioCode, String subjectType, String subjectId,
            JsonNode observations, String inputSnapshotSha256) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (observations == null || !observations.isArray()) return results;
        for (JsonNode observation : observations) {
            String code = observation.path("indicatorCode").asText("");
            if (code.isBlank()) continue;
            List<Map<String, Object>> definitions = jdbc.queryForList("""
                SELECT d.indicator_name,v.version,v.threshold_config::text AS threshold_config
                FROM indicator_definition d
                JOIN indicator_version v ON v.indicator_code=d.indicator_code
                WHERE d.indicator_code=? AND d.status='ACTIVE' AND v.status='ACTIVE'
                  AND (v.effective_from IS NULL OR v.effective_from<=CURRENT_TIMESTAMP)
                  AND (v.effective_to IS NULL OR v.effective_to>CURRENT_TIMESTAMP)
                ORDER BY v.version DESC LIMIT 1
                """, code);
            if (definitions.isEmpty()) {
                throw new IllegalArgumentException("Active text indicator not found: " + code);
            }
            Map<String, Object> definition = definitions.get(0);
            int version = ((Number) definition.get("version")).intValue();
            BigDecimal value = observation.path("value").decimalValue();
            JsonNode thresholds;
            try {
                thresholds = mapper.readTree(
                        Objects.toString(definition.get("threshold_config"), "{}"));
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid threshold config for " + code, ex);
            }
            String riskLevel = riskLevel(value, thresholds);
            String calculationId = observation.path("calculationId").asText("");
            if (calculationId.isBlank()) {
                calculationId = "ICALC-TXT-" + sha256(subjectId + "|" + code + "|"
                        + inputSnapshotSha256).substring(0, 24).toUpperCase();
            }
            Map<String, Object> explanation = new LinkedHashMap<>();
            explanation.put("assertionMode",
                    observation.path("assertionMode").asText("REPORTED"));
            explanation.put("observabilityType",
                    observation.path("observabilityType").asText("TEXT_REPORTED"));
            explanation.put("summary", observation.path("explanation").asText());
            explanation.put("evidenceRefs",
                    mapper.convertValue(observation.path("evidenceRefs"), List.class));
            explanation.put("eventRefs",
                    mapper.convertValue(observation.path("eventRefs"), List.class));
            explanation.put("thresholds", thresholds);
            String snapshot = inputSnapshotSha256 == null || inputSnapshotSha256.isBlank()
                    ? sha256(json(observation)) : inputSnapshotSha256;
            jdbc.update("""
                INSERT INTO indicator_calculation_result
                  (calculation_id,bank_code,scenario_code,subject_type,subject_id,
                   indicator_code,indicator_version,numeric_value,risk_level,explanation,
                   input_snapshot_sha256,algorithm_execution_id)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,NULL)
                ON CONFLICT (calculation_id) DO UPDATE SET
                  numeric_value=EXCLUDED.numeric_value,
                  risk_level=EXCLUDED.risk_level,
                  explanation=EXCLUDED.explanation,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256,
                  calculated_at=CURRENT_TIMESTAMP
                """, calculationId, bankCode, scenarioCode, subjectType, subjectId,
                    code, version, value, riskLevel, json(explanation), snapshot);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("calculationId", calculationId);
            result.put("indicatorCode", code);
            result.put("indicatorVersion", version);
            result.put("indicatorName", definition.get("indicator_name"));
            result.put("value", value);
            result.put("riskLevel", riskLevel);
            result.put("inputSnapshotSha256", snapshot);
            result.put("explanation", explanation);
            results.add(result);
        }
        return results;
    }

    /**
     * Matches worker-extracted semantic observations against the ACTIVE
     * definitions managed by 指标管理. Observations are evidence inputs, never
     * ad-hoc indicator definitions. Only high-confidence matches are persisted
     * as descriptive indicator hits.
     */
    @Transactional
    public List<Map<String, Object>> matchSemanticObservations(
            String bankCode, String scenarioCode, String subjectType, String subjectId,
            JsonNode observations, String inputSnapshotSha256) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (observations == null || !observations.isArray() || observations.isEmpty()) {
            return results;
        }
        List<Map<String, Object>> definitions = jdbc.queryForList("""
            SELECT d.indicator_code,d.indicator_name,d.description,d.observability_type,v.version
            FROM indicator_definition d
            JOIN LATERAL (
              SELECT version
              FROM indicator_version
              WHERE indicator_code=d.indicator_code AND status='ACTIVE'
                AND (effective_from IS NULL OR effective_from<=CURRENT_TIMESTAMP)
                AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP)
              ORDER BY version DESC LIMIT 1
            ) v ON true
            WHERE d.status='ACTIVE' AND d.indicator_code NOT LIKE 'TXT\\_%' ESCAPE '\\'
              AND COALESCE(d.observability_type,'DESCRIPTIVE')='DESCRIPTIVE'
            ORDER BY d.indicator_code
            """);
        String observationText = semanticObservationText(observations);
        Set<String> observedConcepts = concepts(observationText);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> definition : definitions) {
            String definitionName = Objects.toString(
                    definition.get("indicator_name"), "");
            String description = Objects.toString(
                    definition.get("description"), "");
            if (hasUnsupportedScenario(
                    definitionName + " " + description, observationText)) continue;
            String definitionText =
                    stripKnowledgePrefix(definitionName) + " " + description;
            Set<String> definitionConcepts = concepts(definitionText);
            Set<String> shared = new LinkedHashSet<>(definitionConcepts);
            shared.retainAll(observedConcepts);
            if (shared.size() < 2) continue;
            double coverage = (double) shared.size()
                    / Math.max(1, definitionConcepts.size());
            double lexical = bigramDice(observationText, definitionText);
            double score = Math.min(0.99, coverage * 0.78 + lexical * 0.22
                    + (shared.size() >= 3 ? 0.08 : 0));
            if (score < 0.38) continue;
            Map<String, Object> candidate = new LinkedHashMap<>(definition);
            candidate.put("semanticScore", score);
            candidate.put("sharedConcepts", new ArrayList<>(shared));
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble(
                item -> -((Number) item.get("semanticScore")).doubleValue()));
        Set<String> uncoveredObservations = new LinkedHashSet<>();
        observations.forEach(item -> uncoveredObservations.add(
                item.path("observationCode").asText()));
        for (Map<String, Object> candidate : candidates) {
            List<String> sourceObservationCodes = supportingObservationCodes(
                    observations, (List<?>) candidate.get("sharedConcepts"));
            boolean contributes = sourceObservationCodes.stream()
                    .anyMatch(uncoveredObservations::contains);
            if (!contributes) continue;
            double score = ((Number) candidate
                    .get("semanticScore")).doubleValue();
            String code = Objects.toString(candidate.get("indicator_code"));
            int version = ((Number) candidate.get("version")).intValue();
            List<String> eventRefs = observationRefs(
                    observations, sourceObservationCodes, "eventRefs");
            List<Object> evidenceRefs = observationObjects(
                    observations, sourceObservationCodes, "evidenceRefs");
            String observationKey = String.join(",", sourceObservationCodes);
            String calculationId = "ICALC-SEM-" + sha256(subjectId + "|"
                    + observationKey + "|" + code + "|" + inputSnapshotSha256)
                    .substring(0, 24).toUpperCase();
            Map<String, Object> explanation = new LinkedHashMap<>();
            explanation.put("assertionMode", "SEMANTIC_MATCH");
            explanation.put("observabilityType", "DESCRIPTIVE");
            explanation.put("matchStatus", "HIT");
            explanation.put("semanticScore", score);
            explanation.put("semanticFeatures", candidate.get("sharedConcepts"));
            explanation.put("sourceObservationCodes", sourceObservationCodes);
            explanation.put("eventRefs", eventRefs);
            explanation.put("evidenceRefs", evidenceRefs);
            explanation.put("matcherVersion", "INDICATOR_SEMANTIC_MATCHER_1.2");
            explanation.put("decisionBoundary",
                    "该结果表示案例语义观察组合与正式指标定义匹配；"
                            + "仍需结合原始材料或结构化数据复核。");
            String snapshot = inputSnapshotSha256 == null
                    || inputSnapshotSha256.isBlank()
                    ? sha256(observationText) : inputSnapshotSha256;
            jdbc.update("""
                INSERT INTO indicator_calculation_result
                  (calculation_id,bank_code,scenario_code,subject_type,subject_id,
                   indicator_code,indicator_version,numeric_value,text_value,risk_level,
                   explanation,input_snapshot_sha256,algorithm_execution_id)
                VALUES (?,?,?,?,?,?,?,NULL,'HIT','HIGH',?::jsonb,?,NULL)
                ON CONFLICT (calculation_id) DO UPDATE SET
                  text_value='HIT',risk_level='HIGH',explanation=EXCLUDED.explanation,
                  input_snapshot_sha256=EXCLUDED.input_snapshot_sha256,
                  calculated_at=CURRENT_TIMESTAMP
                """, calculationId, bankCode, scenarioCode, subjectType, subjectId,
                    code, version, json(explanation), snapshot);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("calculationId", calculationId);
            result.put("indicatorCode", code);
            result.put("indicatorVersion", version);
            result.put("indicatorName", candidate.get("indicator_name"));
            result.put("textValue", "HIT");
            result.put("riskLevel", "HIGH");
            result.put("semanticScore", score);
            result.put("sourceObservationCodes", sourceObservationCodes);
            result.put("inputSnapshotSha256", snapshot);
            result.put("explanation", explanation);
            results.add(result);
            uncoveredObservations.removeAll(sourceObservationCodes);
            if (uncoveredObservations.isEmpty() || results.size() >= 12) break;
        }
        return results;
    }

    private String semanticObservationText(JsonNode observation) {
        if (observation.isArray()) {
            StringBuilder combined = new StringBuilder();
            observation.forEach(item ->
                    combined.append(' ').append(semanticObservationText(item)));
            return combined.toString();
        }
        StringBuilder text = new StringBuilder();
        text.append(' ').append(observation.path("observationName").asText());
        text.append(' ').append(observation.path("explanation").asText());
        for (JsonNode evidence : observation.path("evidenceRefs")) {
            text.append(' ').append(evidence.path("term").asText());
            text.append(' ').append(evidence.path("quote").asText());
        }
        return text.toString();
    }

    private List<String> supportingObservationCodes(JsonNode observations, List<?> features) {
        Set<String> required = new LinkedHashSet<>();
        features.forEach(value -> required.add(Objects.toString(value)));
        List<String> result = new ArrayList<>();
        for (JsonNode observation : observations) {
            StringBuilder text = new StringBuilder()
                    .append(observation.path("observationName").asText()).append(' ')
                    .append(observation.path("explanation").asText());
            observation.path("evidenceRefs").forEach(evidence ->
                    text.append(' ').append(evidence.path("term").asText()));
            Set<String> overlap = concepts(text.toString());
            overlap.retainAll(required);
            if (overlap.size() >= Math.min(2, required.size())) {
                result.add(observation.path("observationCode").asText());
            }
        }
        return result;
    }

    private List<String> observationRefs(JsonNode observations, List<String> codes, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode observation : observations) {
            if (!codes.contains(observation.path("observationCode").asText())) continue;
            observation.path(field).forEach(item -> result.add(item.asText()));
        }
        return new ArrayList<>(result);
    }

    private List<Object> observationObjects(JsonNode observations, List<String> codes, String field) {
        List<Object> result = new ArrayList<>();
        for (JsonNode observation : observations) {
            if (!codes.contains(observation.path("observationCode").asText())) continue;
            observation.path(field).forEach(item ->
                    result.add(mapper.convertValue(item, Object.class)));
        }
        return result;
    }

    private String stripKnowledgePrefix(String text) {
        int delimiter = Math.max(text.indexOf('：'), text.indexOf(':'));
        return delimiter >= 0 && delimiter + 1 < text.length()
                ? text.substring(delimiter + 1) : text;
    }

    private Set<String> concepts(String value) {
        String text = normalize(value);
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put("HIGH_FREQUENCY", List.of("高频", "频繁", "密集", "大量交易"));
        dictionary.put("SMALL_AMOUNT", List.of("小额", "微额", "微交易", "金额较小"));
        dictionary.put("SHORT_WINDOW", List.of("短时间", "短期", "随即", "迅速", "快速"));
        dictionary.put("RAPID_FLOW", List.of("快进快出", "快速转出", "迅速转出", "不留余额", "过渡"));
        dictionary.put("MULTI_ACCOUNT", List.of("多账户", "多个账户", "关联账户", "多层账户", "逐级"));
        dictionary.put("INTERMEDIARY", List.of("中介账户", "中介机构", "代理账户", "第三方账户"));
        dictionary.put("NO_ECONOMIC_REASON", List.of("缺乏经济理由", "无商业理由", "无合理理由", "背景不符"));
        dictionary.put("PROFILE_MISMATCH", List.of("收入不符", "职业不符", "背景不符", "典型活动不符"));
        dictionary.put("ROUND_AMOUNT", List.of("整数倍", "整数金额", "固定金额"));
        dictionary.put("CROSS_REGION", List.of("跨区域", "不同地区", "多个省份", "开户地分散"));
        dictionary.put("CROSS_BORDER", List.of("跨境", "境外", "跨国", "国际电汇"));
        dictionary.put("THIRD_PARTY", List.of("第三方", "支付宝", "微信支付"));
        dictionary.put("MOBILE_PAYMENT", List.of(
                "移动支付", "第三方支付", "支付宝", "微信支付", "支付平台"));
        dictionary.put("NIGHT", List.of("夜间", "凌晨", "深夜"));
        dictionary.put("DEPOSIT", List.of("存入", "存款", "入账"));
        dictionary.put("TRANSFER", List.of("转账", "转移", "流转", "汇款", "付款", "收款"));
        dictionary.put("AGGREGATION", List.of("汇总", "聚合", "归集", "累计"));
        dictionary.put("TRANSACTION", List.of("交易", "资金活动", "收付"));
        dictionary.put("STRUCTURING", List.of("微结构化", "结构化拆分", "分笔", "分拆", "接近阈值"));
        dictionary.put("THRESHOLD", List.of("阈值", "限额", "低于5万", "低于 5 万"));
        dictionary.put("CASH", List.of("现金", "取现", "钞票"));
        dictionary.put("PRECIOUS_METAL", List.of("黄金", "金条", "贵金属"));
        dictionary.put("CRYPTO_ASSET", List.of("加密货币", "虚拟货币", "usdt", "数字资产"));
        dictionary.put("CROSS_CHAIN", List.of("跨链", "混币", "defi"));
        dictionary.put("ASSET_CONVERSION", List.of("转换", "兑换", "变现", "购买"));
        dictionary.put("DISGUISED_BUSINESS", List.of("虚构销售", "贷款方案", "服务费名义", "伪装"));
        Set<String> result = new LinkedHashSet<>();
        dictionary.forEach((concept, terms) -> {
            if (terms.stream().anyMatch(text::contains)) result.add(concept);
        });
        return result;
    }

    /**
     * A generic transaction signal must not be used to infer an unrelated
     * business scenario.  If an indicator definition names a distinctive
     * scenario, the case material must name that scenario as well.
     */
    private boolean hasUnsupportedScenario(String definitionText, String observationText) {
        String definition = normalize(definitionText);
        String observation = normalize(observationText);
        String scenarioPrefix = scenarioPrefix(definitionText);
        if (!scenarioPrefix.isBlank()
                && !observation.contains(normalize(scenarioPrefix))
                && !supportedScenarioAlias(scenarioPrefix, observation)) {
            return true;
        }
        List<List<String>> scenarioTerms = List.of(
                List.of("测试支付", "阈值测试"),
                List.of("预付费欺诈", "预付款欺诈"),
                List.of("虚假招聘", "招聘骗局"),
                List.of("黄金", "贵金属"),
                List.of("加密货币", "虚拟货币", "usdt"),
                List.of("atm", "自动取款机", "柜员机"),
                List.of("defi", "跨链"),
                List.of("网络游戏", "博彩", "赌博"),
                List.of("保险"),
                List.of("房地产", "房产"),
                List.of("贸易融资", "进出口贸易"),
                List.of("慈善", "募捐"),
                List.of("武器", "军火"),
                List.of("彩票"),
                List.of("艺术品", "古董")
        );
        for (List<String> aliases : scenarioTerms) {
            boolean definitionRequires = aliases.stream()
                    .map(this::normalize).anyMatch(definition::contains);
            boolean observationSupports = aliases.stream()
                    .map(this::normalize).anyMatch(observation::contains);
            if (definitionRequires && !observationSupports) return true;
        }
        return false;
    }

    private String scenarioPrefix(String definitionText) {
        int delimiter = definitionText.indexOf('：');
        if (delimiter < 0) delimiter = definitionText.indexOf(':');
        if (delimiter <= 0 || delimiter > 16) return "";
        return definitionText.substring(0, delimiter).trim();
    }

    private boolean supportedScenarioAlias(String prefix, String normalizedObservation) {
        String normalizedPrefix = normalize(prefix);
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("移动支付系统", List.of("移动支付", "支付宝", "微信支付", "第三方支付"));
        aliases.put("微结构化", List.of("小额", "整数倍", "拆分", "接近阈值"));
        aliases.put("结构化", List.of("小额", "整数倍", "拆分", "接近阈值"));
        aliases.put("分层", List.of("多账户", "逐级转", "层层", "资金转移网络"));
        aliases.put("通道账户", List.of("快进快出", "过渡", "不留余额"));
        aliases.put("资金过渡", List.of("快进快出", "过渡", "不留余额"));
        aliases.put("跨境支付路由", List.of("跨境", "境外", "跨境电汇", "跨境外流"));
        aliases.put("跨境代理中介", List.of("跨境", "境外", "代理", "中介"));
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            if (!normalizedPrefix.contains(normalize(entry.getKey()))) continue;
            return entry.getValue().stream().map(this::normalize)
                    .anyMatch(normalizedObservation::contains);
        }
        return false;
    }

    private double bigramDice(String left, String right) {
        Set<String> a = bigrams(normalize(left));
        Set<String> b = bigrams(normalize(right));
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> overlap = new LinkedHashSet<>(a);
        overlap.retainAll(b);
        return (2.0 * overlap.size()) / (a.size() + b.size());
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return Objects.toString(value, "").toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private List<Map<String, Object>> queryRequested(List<String> codes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String code : codes.stream().distinct().toList()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT indicator_code,version,expression::text AS expression,
                       threshold_config::text AS threshold_config,algorithm_id,algorithm_version
                FROM indicator_version
                WHERE indicator_code=? AND status='ACTIVE'
                  AND (effective_from IS NULL OR effective_from<=CURRENT_TIMESTAMP)
                  AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP)
                ORDER BY version DESC LIMIT 1
                """, code);
            if (rows.isEmpty()) throw new IllegalArgumentException("Active indicator not found: " + code);
            result.add(rows.get(0));
        }
        return result;
    }

    private String riskLevel(BigDecimal value, JsonNode thresholds) {
        if (thresholds.has("high") && value.compareTo(thresholds.path("high").decimalValue()) >= 0) return "HIGH";
        if (thresholds.has("medium") && value.compareTo(thresholds.path("medium").decimalValue()) >= 0) return "MEDIUM";
        return "LOW";
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Value is not valid JSON", ex); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
