package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts extracted event instances into auditable semantic instances.
 *
 * <p>The service deliberately separates two decisions: quality describes how
 * trustworthy an instance is; identity describes whether two observations are
 * the same business event. A low-quality event is not silently discarded and
 * weak composite keys never trigger an automatic merge.</p>
 */
@Service
public class EventSemanticEnrichmentService {
    private static final String POLICY_VERSION = "1.0";
    private static final java.util.Set<String> PAYMENT_TRANSFER_FRAMES = java.util.Set.of(
            "FUNDS_TRANSFER", "STRUCTURED_TRANSFER", "INTERMEDIARY_TRANSFER",
            "BANK_TRANSFER", "WIRE_TRANSFER");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventSemanticEnrichmentService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> enrichCase(String caseId) {
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT event_id,bank_code,event_frame_code,event_frame_version,event_time,confidence,
                   definition_match_confidence,definition_match_method,evidence_refs::text AS evidence_refs,
                   dedup_key
            FROM cf_risk_event
            WHERE case_id=? AND deleted=false
            ORDER BY created_at,event_id
            """, caseId);
        int strongMatches = 0;
        for (Map<String, Object> event : events) {
            Map<String, Object> evidence = parseEvidence(event.get("evidence_refs"));
            QualityScore quality = score(new QualityInput(
                    sourceReliability(event.get("definition_match_method")),
                    completeness(event, evidence),
                    event.get("event_time") == null ? 0.35 : 1.0,
                    hasAny(evidence, "accountHash", "accountId", "customerId", "subjectId") ? 0.90 : 0.50,
                    number(event.get("definition_match_confidence"),
                            number(event.get("confidence"), 0.50))));

            String strongKey = firstText(evidence, "uetr", "transactionId", "endToEndId",
                    "sourceRecordId", "source_record_id");
            boolean matched = strongKey != null && !strongKey.isBlank();
            String canonicalId = matched
                    ? "CE-" + sha256(Objects.toString(event.get("bank_code"), "") + "|"
                        + Objects.toString(event.get("definition_match_method"), "") + "|"
                        + Objects.toString(event.get("event_frame_code"), "") + "|" + strongKey)
                        .substring(0, 24).toUpperCase()
                    : Objects.toString(event.get("event_id"));
            if (matched) strongMatches++;
            String frameCode = Objects.toString(event.get("event_frame_code"), "");
            boolean paymentTransfer = PAYMENT_TRANSFER_FRAMES.contains(frameCode);
            String profileCode = semanticProfileFor(frameCode);
            Integer profileVersion = profileCode == null ? null : 1;
            String lifecycleCode = paymentTransfer ? "PAYMENT.CREDIT_TRANSFER" : null;
            Integer lifecycleVersion = lifecycleCode == null ? null : 1;

            jdbc.update("""
                UPDATE cf_risk_event
                SET semantic_profile_code=?,
                    semantic_profile_version=?,
                    lifecycle_code=?,
                    lifecycle_version=?,
                    canonical_event_id=?,
                    identity_resolution_status=?,
                    event_quality_score=?,
                    quality_breakdown=?::jsonb,
                    quality_policy_version=?
                WHERE event_id=?
                """, profileCode, profileVersion, lifecycleCode, lifecycleVersion,
                    canonicalId, matched ? "MATCHED" : "DISTINCT", quality.score(),
                    json(quality.breakdown()), POLICY_VERSION, event.get("event_id"));
        }
        return Map.of("caseId", caseId, "eventCount", events.size(),
                "strongIdentityMatches", strongMatches, "qualityPolicyVersion", POLICY_VERSION);
    }

    String semanticProfileFor(String frameCode) {
        if (frameCode == null || frameCode.isBlank()) return null;
        if (PAYMENT_TRANSFER_FRAMES.contains(frameCode)) return "PAYMENT.CREDIT_TRANSFER";
        if ("CASH_DEPOSIT".equals(frameCode)) return "CASH.DEPOSIT";
        return "LEGACY." + frameCode;
    }

    public QualityScore score(QualityInput input) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("sourceReliability", clamp(input.sourceReliability()));
        breakdown.put("completeness", clamp(input.completeness()));
        breakdown.put("temporalPrecision", clamp(input.temporalPrecision()));
        breakdown.put("entityResolution", clamp(input.entityResolution()));
        breakdown.put("extractionConfidence", clamp(input.extractionConfidence()));
        double value = breakdown.get("sourceReliability") * 0.30
                + breakdown.get("completeness") * 0.20
                + breakdown.get("temporalPrecision") * 0.15
                + breakdown.get("entityResolution") * 0.20
                + breakdown.get("extractionConfidence") * 0.15;
        BigDecimal score = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
        String grade = value >= 0.85 ? "HIGH" : value >= 0.65 ? "MEDIUM"
                : value >= 0.40 ? "LOW" : "INSUFFICIENT";
        return new QualityScore(score, grade, breakdown);
    }

    public IdentityDecision resolve(Map<String, ?> left, Map<String, ?> right) {
        for (String key : List.of("uetr", "transactionId", "endToEndId")) {
            String a = text(left.get(key));
            String b = text(right.get(key));
            if (a != null && a.equals(b)) return new IdentityDecision("MATCHED", 1.0, key);
        }
        double score = 0;
        double total = 0;
        for (Map.Entry<String, Double> criterion : Map.of(
                "amount", 0.25, "currency", 0.10, "debtor", 0.20,
                "creditor", 0.20, "eventTime", 0.25).entrySet()) {
            total += criterion.getValue();
            String a = text(left.get(criterion.getKey()));
            String b = text(right.get(criterion.getKey()));
            if (a != null && a.equals(b)) score += criterion.getValue();
        }
        double normalized = total == 0 ? 0 : score / total;
        // Composite equality remains a review candidate; it is never auto-merged.
        return normalized >= 0.80
                ? new IdentityDecision("CANDIDATE", normalized, "composite")
                : new IdentityDecision("DISTINCT", normalized, "composite");
    }

    private Map<String, Object> parseEvidence(Object raw) {
        if (raw == null) return Map.of();
        try {
            Object value = mapper.readValue(String.valueOf(raw), Object.class);
            Map<String, Object> flattened = new LinkedHashMap<>();
            flatten(value, flattened);
            return flattened;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void flatten(Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                target.putIfAbsent(String.valueOf(key), child);
                flatten(child, target);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> flatten(item, target));
        }
    }

    private double completeness(Map<String, Object> event, Map<String, Object> evidence) {
        int present = 0;
        if (event.get("event_frame_code") != null) present++;
        if (event.get("event_time") != null) present++;
        if (!evidence.isEmpty()) present++;
        if (hasAny(evidence, "amount", "transactionId", "sourceRecordId", "accountHash")) present++;
        return present / 4.0;
    }

    private double sourceReliability(Object method) {
        String value = Objects.toString(method, "");
        if (value.contains("MANUAL")) return 0.90;
        if (value.contains("STRUCTURED") || value.contains("DICTIONARY")) return 0.95;
        if (value.contains("WORKER") || value.contains("RULE")) return 0.80;
        return 0.60;
    }

    private boolean hasAny(Map<String, Object> values, String... keys) {
        for (String key : keys) if (text(values.get(key)) != null) return true;
        return false;
    }

    private String firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = text(values.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot serialize event quality", ex); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record QualityInput(double sourceReliability, double completeness,
                               double temporalPrecision, double entityResolution,
                               double extractionConfidence) {}
    public record QualityScore(BigDecimal score, String grade, Map<String, Double> breakdown) {}
    public record IdentityDecision(String status, double score, String matchedBy) {}
}
