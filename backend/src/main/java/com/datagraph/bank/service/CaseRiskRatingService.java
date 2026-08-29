package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic, explainable default rating; the reviewer owns the final level. */
@Service
public class CaseRiskRatingService {
    private final ObjectMapper json;

    public CaseRiskRatingService(ObjectMapper json) {
        this.json = json;
    }

    public Rating rate(JsonNode framework, JsonNode similarity) {
        JsonNode safeFramework = framework == null ? json.createObjectNode() : framework;
        JsonNode safeSimilarity = similarity == null ? json.createObjectNode() : similarity;
        int events = safeFramework.path("events").size();
        int relationships = safeFramework.path("relationships").size();
        int customers = safeFramework.path("customers").size();
        int accounts = safeFramework.path("accounts").size();
        int entities = safeFramework.path("other_entities").size();

        double eventSeverity = Math.min(30, events * 4.5);
        String corpus = safeFramework.toString().toLowerCase(Locale.ROOT);
        int severeHits = keywordHits(corpus,
                "洗钱", "诈骗", "涉案", "团伙", "地下钱庄", "跑分", "黑名单", "司法", "制裁", "高风险");
        double typology = Math.min(22, severeHits * 3.2);
        double network = Math.min(16, relationships * 1.25 + Math.max(0, relationships - events) * .5);
        double exposure = Math.min(12, customers * 2.0 + accounts * 1.4 + entities * .8);

        JsonNode matches = safeSimilarity.path("matches");
        if (!matches.isArray()) matches = safeSimilarity.path("similarCases");
        double similarityRisk = 0;
        if (matches.isArray()) {
            for (JsonNode match : matches) {
                double value = match.path("similarity").asDouble(0);
                if (value > 1) value /= 100.0;
                similarityRisk = Math.max(similarityRisk, Math.min(15, value * 15));
            }
        }
        String rawHint = safeFramework.path("basic_info").path("risk_level").asText("").toUpperCase(Locale.ROOT);
        double sourceHint = rawHint.contains("高") || rawHint.contains("HIGH") ? 5
                : rawHint.contains("中") || rawHint.contains("MEDIUM") ? 3 : 0;
        double scoreValue = Math.min(100, eventSeverity + typology + network + exposure + similarityRisk + sourceHint);
        BigDecimal score = BigDecimal.valueOf(scoreValue).setScale(2, RoundingMode.HALF_UP);
        String level = scoreValue >= 70 ? "HIGH" : scoreValue >= 40 ? "MEDIUM" : "LOW";

        Map<String,Object> breakdown = new LinkedHashMap<>();
        breakdown.put("eventSeverity", round(eventSeverity));
        breakdown.put("typologyHits", round(typology));
        breakdown.put("networkComplexity", round(network));
        breakdown.put("subjectExposure", round(exposure));
        breakdown.put("similarCaseRisk", round(similarityRisk));
        breakdown.put("sourceRiskHint", round(sourceHint));
        breakdown.put("facts", Map.of("events", events, "relationships", relationships,
                "customers", customers, "accounts", accounts, "otherEntities", entities,
                "riskKeywordHits", severeHits));
        breakdown.put("thresholds", Map.of("LOW", "0-39.99", "MEDIUM", "40-69.99", "HIGH", "70-100"));
        breakdown.put("modelVersion", "EXPLAINABLE_CASE_RISK_V1");
        return new Rating(score, level, breakdown);
    }

    private int keywordHits(String text, String... keywords) {
        int count = 0;
        for (String keyword : keywords) if (text.contains(keyword.toLowerCase(Locale.ROOT))) count++;
        return count;
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public record Rating(BigDecimal score, String level, Map<String,Object> breakdown) {}
}
