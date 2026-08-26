package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ContractProjectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VersionedContractService contracts;

    public ContractProjectionService(JdbcTemplate jdbc, ObjectMapper mapper,
                                     VersionedContractService contracts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.contracts = contracts;
    }

    @Transactional
    public Map<String, Object> captureCase(String caseId) {
        Map<String, Object> riskCase = jdbc.queryForMap("""
            SELECT * FROM cf_risk_case WHERE case_id=? AND deleted=false
            """, caseId);
        String bankCode = text(riskCase.get("bank_code"));
        List<Map<String, Object>> rawEvents = jdbc.queryForList("""
            SELECT * FROM cf_risk_event WHERE case_id=? AND deleted=false ORDER BY event_time NULLS LAST,id
            """, caseId);
        List<JsonNode> canonicalEvents = new ArrayList<>();
        List<Map<String, Object>> eventInstances = new ArrayList<>();
        for (Map<String, Object> event : rawEvents) {
            ObjectNode canonical = canonicalEvent(riskCase, event);
            Map<String, Object> stored = contracts.saveProjected(VersionedContractService.CANONICAL_EVENT,
                    VersionedContractService.V1, bankCode, text(event.get("event_id")), canonical,
                    "CF_RISK_EVENT", text(event.get("event_id")), "contract-adapter");
            jdbc.update("""
                UPDATE cf_risk_event SET canonical_contract_instance_id=?,event_kind=? WHERE event_id=?
                """, stored.get("instanceId"), canonical.path("eventKind").asText(), event.get("event_id"));
            canonicalEvents.add(canonical);
            eventInstances.add(stored);
        }

        List<Map<String, Object>> signals = jdbc.queryForList("""
            SELECT s.*,t.account_hash,t.counterparty_hash,t.amount,t.currency,t.occurred_at,
                   t.channel,t.transaction_type,t.source_record_id
            FROM case_signal_rel r JOIN risk_signal s ON s.signal_id=r.signal_id
            LEFT JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
            WHERE r.case_id=? ORDER BY s.created_at
            """, caseId);
        captureSignals(bankCode, signals);

        ArrayNode entities = mapper.createArrayNode();
        Set<String> entityIds = new LinkedHashSet<>();
        for (Map<String, Object> signal : signals) {
            addAccountEntity(entities, entityIds, bankCode, signal.get("account_hash"));
            addAccountEntity(entities, entityIds, bankCode, signal.get("counterparty_hash"));
        }
        for (JsonNode event : canonicalEvents) for (JsonNode participant : event.path("participants"))
            addReferencedEntity(entities, entityIds, participant.path("entityUid").asText(),
                    participant.path("role").asText());
        ArrayNode evidences = mapper.createArrayNode();
        for (Map<String, Object> signal : signals) {
            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("evidenceId", text(signal.get("signal_id")));
            evidence.put("type", "RISK_SIGNAL");
            evidence.put("sourceRefType", text(signal.get("source_ref_type")));
            evidence.put("sourceRefId", text(signal.get("source_ref_id")));
            evidence.put("algorithmId", text(signal.get("algorithm_id")));
            evidence.put("algorithmVersion", text(signal.get("algorithm_version")));
            putNumber(evidence, "score", signal.get("score"));
            evidence.set("reasonCodes", mapper.valueToTree(sqlArray(signal.get("reason_codes"))));
            evidences.add(evidence);
        }

        ArrayNode relationships = mapper.createArrayNode();
        Set<String> relationshipKeys = new LinkedHashSet<>();
        for (JsonNode event : canonicalEvents) {
            addRelation(relationships, relationshipKeys, "CONTAINS_EVENT", caseId, event.path("eventId").asText());
            for (JsonNode participant : event.path("participants"))
                addRelation(relationships, relationshipKeys, "INVOLVES", event.path("eventId").asText(),
                        participant.path("entityUid").asText());
        }
        for (JsonNode evidence : evidences)
            addRelation(relationships, relationshipKeys, "SUPPORTED_BY", caseId, evidence.path("evidenceId").asText());

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("contractVersion", "CaseGraphEnvelope/1.0");
        envelope.put("envelopeId", "CGE-" + caseId + "-" + riskCase.getOrDefault("case_version", 1));
        ObjectNode tenant = envelope.putObject("tenant");
        tenant.put("bankCode", bankCode);
        if (riskCase.get("workspace_id") == null) tenant.putNull("workspaceId");
        else tenant.put("workspaceId", ((Number) riskCase.get("workspace_id")).longValue());
        ObjectNode caseNode = mapper.valueToTree(riskCase);
        caseNode.put("caseId", caseId);
        caseNode.put("caseVersion", ((Number) riskCase.getOrDefault("case_version", 1)).longValue());
        envelope.set("case", caseNode);
        envelope.set("entities", entities);
        ArrayNode eventArray = mapper.createArrayNode();
        canonicalEvents.forEach(eventArray::add);
        envelope.set("events", eventArray);
        envelope.set("evidences", evidences);
        envelope.set("relationships", relationships);
        envelope.put("schemaVersion", "bankgraph-case-graph/1.0");
        ArrayNode sourceRefs = envelope.putArray("sourceRefs");
        for (Map<String, Object> signal : signals) {
            ObjectNode ref = sourceRefs.addObject();
            ref.put("type", "RISK_SIGNAL");
            ref.put("id", text(signal.get("signal_id")));
        }
        ObjectNode producer = envelope.putObject("producer");
        producer.put("type", "ADAPTER");
        producer.put("id", "CURRENT_CASE_PROJECTION");
        producer.put("version", "1.0");
        envelope.put("generatedAt", time(riskCase.get("updated_at") == null
                ? riskCase.get("created_at") : riskCase.get("updated_at")));
        if (riskCase.get("risk_score") == null) envelope.putNull("confidence");
        else putNumber(envelope, "confidence", riskCase.get("risk_score"));
        envelope.put("contentHash", contracts.semanticHash(envelope));

        Map<String, Object> stored = contracts.saveProjected(VersionedContractService.CASE_GRAPH,
                VersionedContractService.V1, bankCode, caseId, envelope,
                "CF_RISK_CASE", caseId, "contract-adapter");
        jdbc.update("""
            UPDATE cf_risk_case SET case_graph_contract_instance_id=?,case_graph_contract_sha256=?
            WHERE case_id=?
            """, stored.get("instanceId"), stored.get("contentSha256"), caseId);
        Map<String, Object> result = new LinkedHashMap<>(stored);
        result.put("canonicalEvents", eventInstances);
        return result;
    }

    @Transactional
    public List<Map<String, Object>> captureInferenceRun(String runId) {
        Map<String, Object> run = jdbc.queryForMap("""
            SELECT r.*,s.snapshot_sha256,s.schema_version AS snapshot_schema_version
            FROM analytics_run r JOIN analytics_dataset_snapshot s ON s.snapshot_id=r.input_snapshot_id
            WHERE r.run_id=?
            """, runId);
        String bankCode = text(run.get("bank_code"));
        List<Map<String, Object>> stored = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM inference_evidence WHERE run_id=? ORDER BY id", runId)) {
            ObjectNode payload = inferencePayload(run, row);
            Map<String, Object> instance = contracts.saveProjected(VersionedContractService.INFERENCE_EVIDENCE,
                    VersionedContractService.V1, bankCode, text(row.get("evidence_id")), payload,
                    "ANALYTICS_RUN", runId, "contract-adapter");
            jdbc.update("""
                UPDATE inference_evidence SET contract_instance_id=?,evidence_type=?,target_type=?,target_id=?
                WHERE evidence_id=?
                """, instance.get("instanceId"), payload.path("evidenceType").asText(),
                    payload.path("target").path("type").asText(), payload.path("target").path("id").asText(),
                    row.get("evidence_id"));
            stored.add(instance);
        }
        return stored;
    }

    public ObjectNode normalizeExchange(JsonNode input, String partnerCode, String receiverInstitution,
                                        String timestamp) {
        if ("RiskExchangeEnvelope/1.0".equals(input.path("contractVersion").asText()))
            return input.deepCopy();
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("contractVersion", "RiskExchangeEnvelope/1.0");
        normalized.putObject("sender").put("institutionCode", partnerCode);
        normalized.putObject("receiver").put("institutionCode", receiverInstitution);
        normalized.put("messageId", input.path("messageId").asText());
        normalized.put("idempotencyKey", input.path("idempotencyKey").asText(input.path("messageId").asText()));
        normalized.put("contentType", input.path("messageType").asText(input.path("contentType").asText()));
        normalized.put("contentVersion", input.path("contentVersion").asText("1.0"));
        normalized.put("schemaVersion", input.path("schemaVersion").asText());
        normalized.put("createdAt", input.path("createdAt").asText(timestamp));
        JsonNode payload = input.path("payload");
        normalized.put("payloadDigest", contracts.semanticHash(payload));
        ObjectNode privacy = normalized.putObject("privacyProcessing");
        privacy.put("classification", "RESTRICTED");
        privacy.put("identifierMethod", "UNDECLARED_LEGACY_ADAPTER");
        privacy.put("containsDirectIdentifiers", true);
        normalized.set("payload", payload);
        return normalized;
    }

    private void captureSignals(String bankCode, List<Map<String, Object>> signals) {
        for (Map<String, Object> signal : signals) {
            ObjectNode payload = mapper.createObjectNode();
            String signalId = text(signal.get("signal_id"));
            payload.put("contractVersion", "InferenceEvidence/1.0");
            payload.put("evidenceId", "SIGNAL-EVIDENCE-" + signalId);
            payload.put("runId", "SIGNAL:" + signalId);
            payload.put("evidenceType", signal.get("model_version") == null ? "RULE" : "MODEL");
            ObjectNode target = payload.putObject("target");
            target.put("type", "TRANSACTION");
            target.put("id", text(signal.get("source_ref_id"), signalId));
            ObjectNode producer = payload.putObject("producer");
            producer.put("algorithmId", text(signal.get("algorithm_id"), "UNKNOWN"));
            producer.put("algorithmVersion", text(signal.get("algorithm_version"), "unknown"));
            if (signal.get("model_version") != null) producer.put("modelVersion", text(signal.get("model_version")));
            ObjectNode snapshot = payload.putObject("inputSnapshot");
            snapshot.put("id", text(signal.get("input_data_version"), text(signal.get("source_ref_id"), signalId)));
            snapshot.put("sha256", hashOrFallback(signal.get("input_data_hash"), signalId));
            putNumber(payload, "score", signal.getOrDefault("score", 0));
            payload.putNull("threshold");
            payload.put("decision", text(signal.get("decision"), "UNKNOWN"));
            payload.set("reasonCodes", mapper.valueToTree(sqlArray(signal.get("reason_codes"))));
            payload.set("contributions", jsonNode(signal.get("contribution"), mapper.createArrayNode()));
            payload.putArray("evidenceRefs").addObject().put("type", text(signal.get("source_ref_type"), "SOURCE"))
                    .put("id", text(signal.get("source_ref_id"), signalId));
            payload.putArray("pathEvidence");
            payload.putNull("evidenceSubgraphRef");
            payload.putObject("executionRef").put("runId", "SIGNAL:" + signalId);
            payload.put("generatedAt", time(signal.get("created_at")));
            Map<String, Object> stored = contracts.saveProjected(VersionedContractService.INFERENCE_EVIDENCE,
                    VersionedContractService.V1, bankCode, "SIGNAL:" + signalId, payload,
                    "RISK_SIGNAL", signalId, "contract-adapter");
            jdbc.update("UPDATE risk_signal SET inference_contract_instance_id=? WHERE signal_id=?",
                    stored.get("instanceId"), signalId);
        }
    }

    private ObjectNode inferencePayload(Map<String, Object> run, Map<String, Object> row) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("contractVersion", "InferenceEvidence/1.0");
        payload.put("evidenceId", text(row.get("evidence_id")));
        payload.put("runId", text(run.get("run_id")));
        payload.put("evidenceType", text(row.get("evidence_type"), run.get("model_id") == null ? "STATISTICAL" : "MODEL"));
        ObjectNode target = payload.putObject("target");
        target.put("type", text(row.get("target_type"), "SUBJECT"));
        target.put("id", text(row.get("target_id"), text(row.get("subject_id"), "UNKNOWN")));
        ObjectNode producer = payload.putObject("producer");
        putIfPresent(producer, "modelId", run.get("model_id"));
        putIfPresent(producer, "modelVersion", run.get("model_version"));
        putIfPresent(producer, "algorithmId", run.get("algorithm_id"));
        putIfPresent(producer, "algorithmVersion", run.get("algorithm_version"));
        putIfPresent(producer, "codeHash", run.get("code_hash"));
        ObjectNode snapshot = payload.putObject("inputSnapshot");
        snapshot.put("id", text(run.get("input_snapshot_id")));
        snapshot.put("sha256", text(run.get("snapshot_sha256")));
        if (row.get("window_start") == null && row.get("window_end") == null) payload.putNull("window");
        else {
            ObjectNode window = payload.putObject("window");
            putIfPresent(window, "start", row.get("window_start"));
            putIfPresent(window, "end", row.get("window_end"));
        }
        putNumber(payload, "score", row.getOrDefault("score", 0));
        if (row.get("decision_threshold") == null) payload.putNull("threshold");
        else putNumber(payload, "threshold", row.get("decision_threshold"));
        payload.put("decision", text(row.get("decision"), "UNKNOWN"));
        payload.set("reasonCodes", mapper.valueToTree(sqlArray(row.get("reason_codes"))));
        payload.set("contributions", jsonNode(row.get("contributions"), mapper.createArrayNode()));
        payload.putArray("evidenceRefs");
        payload.set("pathEvidence", jsonNode(row.get("path_evidence"), mapper.createArrayNode()));
        if (row.get("evidence_subgraph_ref") == null) payload.putNull("evidenceSubgraphRef");
        else payload.put("evidenceSubgraphRef", text(row.get("evidence_subgraph_ref")));
        ObjectNode execution = payload.putObject("executionRef");
        execution.put("runId", text(run.get("run_id")));
        putIfPresent(execution, "logId", row.get("execution_log_id"));
        payload.put("generatedAt", time(row.get("created_at")));
        return payload;
    }

    private ObjectNode canonicalEvent(Map<String, Object> riskCase, Map<String, Object> event) {
        ObjectNode payload = mapper.createObjectNode();
        String eventId = text(event.get("event_id"));
        payload.put("contractVersion", "CanonicalEvent/1.0");
        payload.put("eventId", eventId);
        String eventKind = text(event.get("event_kind"), "ATOMIC");
        if (text(event.get("rule_name")).contains("BATCH_PATTERN")) eventKind = "AGGREGATE";
        payload.put("eventKind", eventKind);
        String storedStandardCode = text(event.get("event_standard_code"));
        String scenarioCode = text(riskCase.get("scenario_code"), "UNKNOWN");
        payload.put("standardEventCode", storedStandardCode.isBlank() || storedStandardCode.equals(scenarioCode)
                ? text(event.get("event_type"), "UNKNOWN") : storedStandardCode);
        ObjectNode scenario = payload.putObject("scenario");
        scenario.put("code", text(riskCase.get("scenario_code"), "UNKNOWN"));
        putIfPresent(scenario, "version", riskCase.get("scenario_version"));
        ObjectNode action = payload.putObject("action");
        action.put("code", text(event.get("event_type"), "UNKNOWN"));
        action.put("name", text(event.get("event_name"), "UNKNOWN"));
        JsonNode facts = jsonNode(event.get("evidence_refs"), mapper.createObjectNode());
        ArrayNode participants = payload.putArray("participants");
        addParticipant(participants, riskCase, facts.path("source").asText(""), "SOURCE_ACCOUNT");
        addParticipant(participants, riskCase, facts.path("target").asText(""), "TARGET_ACCOUNT");
        ObjectNode method = payload.putObject("method");
        method.put("channel", facts.path("paymentFormat").asText(facts.path("channel").asText("UNKNOWN")));
        ObjectNode occurred = payload.putObject("occurredAt");
        occurred.put("start", time(event.get("event_time") == null ? event.get("created_at") : event.get("event_time")));
        occurred.putNull("end");
        occurred.put("precision", "SECOND");
        payload.set("location", mapper.createObjectNode());
        ObjectNode result = payload.putObject("result");
        result.put("status", text(event.get("risk_level"), "UNKNOWN"));
        ObjectNode attributes = payload.putObject("attributes");
        copy(facts, attributes, "amount", "currency", "sourceRecordId");
        putNumberIfPresent(attributes, "riskScore", event.get("risk_score"));
        ArrayNode evidenceRefs = payload.putArray("evidenceRefs");
        ObjectNode evidence = evidenceRefs.addObject();
        evidence.put("type", "CF_RISK_EVENT_EVIDENCE");
        evidence.put("id", eventId);
        evidence.set("facts", facts);
        payload.putArray("sourceRefs").addObject().put("type", "CF_RISK_EVENT").put("id", eventId);
        putNumber(payload, "confidence", event.getOrDefault("confidence", 0));
        payload.put("normalizationVersion", "current-case-adapter/1.0");
        if (event.get("dedup_key") == null) payload.putNull("dedupKey");
        else payload.put("dedupKey", text(event.get("dedup_key")));
        return payload;
    }

    private void addParticipant(ArrayNode participants, Map<String, Object> riskCase, String raw, String role) {
        if (raw == null || raw.isBlank()) return;
        ObjectNode participant = participants.addObject();
        participant.put("entityUid", bankEntityUid(text(riskCase.get("bank_code")), raw));
        participant.put("role", role);
    }

    private void addAccountEntity(ArrayNode entities, Set<String> seen, String bankCode, Object hash) {
        String value = text(hash);
        if (value.isBlank()) return;
        String uid = bankEntityUid(bankCode, value);
        if (!seen.add(uid)) return;
        ObjectNode entity = entities.addObject();
        entity.put("entityUid", uid);
        entity.put("entityType", "ACCOUNT");
        entity.put("identityScheme", "BANK_SCOPED_HASH");
        entity.put("identityValue", value);
    }

    private void addReferencedEntity(ArrayNode entities, Set<String> seen, String uid, String role) {
        if (uid == null || uid.isBlank() || !seen.add(uid)) return;
        ObjectNode entity = entities.addObject();
        entity.put("entityUid", uid);
        entity.put("entityType", role.endsWith("ACCOUNT") ? "ACCOUNT" : "SUBJECT");
        entity.put("identityScheme", "CANONICAL_EVENT_PARTICIPANT");
    }

    private String bankEntityUid(String bankCode, String value) {
        return bankCode + ":ACCOUNT:" + contracts.semanticHash(mapper.valueToTree(value));
    }

    private ObjectNode relation(String type, String source, String target) {
        ObjectNode relation = mapper.createObjectNode();
        relation.put("relationshipId", "REL-" + contracts.semanticHash(
                mapper.valueToTree(type + "\n" + source + "\n" + target)).substring(0, 32));
        relation.put("type", type);
        relation.put("source", source);
        relation.put("target", target);
        return relation;
    }

    private void addRelation(ArrayNode relationships, Set<String> keys,
                             String type, String source, String target) {
        String key = type + "\n" + source + "\n" + target;
        if (keys.add(key)) relationships.add(relation(type, source, target));
    }

    private JsonNode jsonNode(Object value, JsonNode fallback) {
        try {
            if (value == null) return fallback;
            if (value instanceof JsonNode node) return node;
            if (value instanceof org.postgresql.util.PGobject pg) return mapper.readTree(pg.getValue());
            if (value instanceof String string) return mapper.readTree(string);
            return mapper.valueToTree(value);
        } catch (Exception ex) { return fallback; }
    }

    private List<String> sqlArray(Object raw) {
        try {
            if (raw instanceof Array array) raw = array.getArray();
            if (raw instanceof Object[] values) {
                List<String> result = new ArrayList<>();
                for (Object value : values) result.add(String.valueOf(value));
                return result;
            }
            if (raw instanceof Iterable<?> values) {
                List<String> result = new ArrayList<>();
                for (Object value : values) result.add(String.valueOf(value));
                return result;
            }
        } catch (Exception ignored) { }
        return List.of();
    }

    private void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) if (source.has(field)) target.set(field, source.get(field));
    }

    private void putIfPresent(ObjectNode node, String field, Object value) {
        if (value != null && !text(value).isBlank()) node.put(field, text(value));
    }

    private void putNumber(ObjectNode node, String field, Object value) {
        try { node.put(field, new java.math.BigDecimal(Objects.toString(value, "0"))); }
        catch (Exception ex) { node.put(field, 0); }
    }

    private void putNumberIfPresent(ObjectNode node, String field, Object value) {
        if (value != null) putNumber(node, field, value);
    }

    private String hashOrFallback(Object raw, String fallback) {
        String value = text(raw);
        return value.matches("[0-9a-f]{64}") ? value : contracts.semanticHash(mapper.valueToTree(fallback));
    }

    private String text(Object value) { return text(value, ""); }

    private String text(Object value, String fallback) {
        String result = Objects.toString(value, "").trim();
        return result.isBlank() ? fallback : result;
    }

    private String time(Object value) {
        if (value == null) return Instant.EPOCH.toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof OffsetDateTime offset) return offset.toInstant().toString();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(ZoneOffset.UTC).toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        return text(value);
    }
}
