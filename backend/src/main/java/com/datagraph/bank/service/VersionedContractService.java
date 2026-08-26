package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class VersionedContractService {
    public static final String CASE_GRAPH = "CASE_GRAPH_ENVELOPE";
    public static final String CANONICAL_EVENT = "CANONICAL_EVENT";
    public static final String INFERENCE_EVIDENCE = "INFERENCE_EVIDENCE";
    public static final String RISK_EXCHANGE = "RISK_EXCHANGE_ENVELOPE";
    public static final String V1 = "1.0";

    private static final Map<String, Builtin> BUILTINS = Map.of(
            CASE_GRAPH, new Builtin("CaseGraphEnvelope v1", "contracts/case-graph-envelope-1.0.json"),
            CANONICAL_EVENT, new Builtin("CanonicalEvent v1", "contracts/canonical-event-1.0.json"),
            INFERENCE_EVIDENCE, new Builtin("InferenceEvidence v1", "contracts/inference-evidence-1.0.json"),
            RISK_EXCHANGE, new Builtin("RiskExchangeEnvelope v1", "contracts/risk-exchange-envelope-1.0.json"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VersionedContractService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void registerBuiltins() {
        BUILTINS.forEach((code, builtin) -> {
            try {
                String schema;
                try (var input = new ClassPathResource(builtin.resource()).getInputStream()) {
                    schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                jdbc.update("""
                    INSERT INTO contract_definition
                      (contract_code,contract_version,contract_name,description,schema_json,
                       compatibility_policy,status,created_by)
                    VALUES (?,?,?,'BankGraph built-in versioned contract',?::jsonb,'BACKWARD','ACTIVE','system')
                    ON CONFLICT (contract_code,contract_version) DO NOTHING
                    """, code, V1, builtin.name(), schema);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to register built-in contract " + code, ex);
            }
        });
    }

    public List<Map<String, Object>> definitions() {
        return jdbc.queryForList("""
            SELECT contract_code AS "contractCode",contract_version AS "contractVersion",
                   contract_name AS "contractName",description,compatibility_policy AS "compatibilityPolicy",
                   status,created_by AS "createdBy",created_at AS "createdAt",updated_at AS "updatedAt"
            FROM contract_definition ORDER BY contract_code,contract_version DESC
            """);
    }

    public Map<String, Object> definition(String code, String version) {
        return jsonFields(jdbc.queryForMap("""
            SELECT contract_code AS "contractCode",contract_version AS "contractVersion",
                   contract_name AS "contractName",description,schema_json AS "schema",
                   compatibility_policy AS "compatibilityPolicy",status,created_by AS "createdBy",
                   created_at AS "createdAt",updated_at AS "updatedAt"
            FROM contract_definition WHERE contract_code=? AND contract_version=?
            """, normalizeCode(code), version), "schema");
    }

    public Map<String, Object> createDefinition(Map<String, Object> body, String username) {
        String code = requiredText(body, "contractCode").toUpperCase();
        String version = requiredText(body, "contractVersion");
        JsonNode schema = mapper.valueToTree(body.get("schema"));
        if (!schema.isObject()) throw new IllegalArgumentException("schema must be a JSON object");
        jdbc.update("""
            INSERT INTO contract_definition
              (contract_code,contract_version,contract_name,description,schema_json,
               compatibility_policy,status,created_by)
            VALUES (?,?,?,?,?::jsonb,?,'DRAFT',?)
            """, code, version, requiredText(body, "contractName"), body.get("description"),
                json(schema), Objects.toString(body.getOrDefault("compatibilityPolicy", "BACKWARD")), username);
        return definition(code, version);
    }

    public Map<String, Object> updateDefinition(String code, String version, Map<String, Object> body) {
        Map<String, Object> current = definition(code, version);
        if (!"DRAFT".equals(current.get("status")))
            throw new IllegalStateException("Only DRAFT contract definitions can be updated");
        JsonNode schema = mapper.valueToTree(body.get("schema"));
        if (!schema.isObject()) throw new IllegalArgumentException("schema must be a JSON object");
        jdbc.update("""
            UPDATE contract_definition SET contract_name=?,description=?,schema_json=?::jsonb,
                   compatibility_policy=?,updated_at=CURRENT_TIMESTAMP
            WHERE contract_code=? AND contract_version=?
            """, requiredText(body, "contractName"), body.get("description"), json(schema),
                Objects.toString(body.getOrDefault("compatibilityPolicy", "BACKWARD")),
                normalizeCode(code), version);
        return definition(code, version);
    }

    public Map<String, Object> updateDefinitionStatus(String code, String version, String status) {
        String normalized = status.toUpperCase();
        if (!List.of("DRAFT", "ACTIVE", "RETIRED").contains(normalized))
            throw new IllegalArgumentException("Unsupported contract status: " + status);
        jdbc.update("""
            UPDATE contract_definition SET status=?,updated_at=CURRENT_TIMESTAMP
            WHERE contract_code=? AND contract_version=?
            """, normalized, normalizeCode(code), version);
        return definition(code, version);
    }

    public void deleteDefinition(String code, String version) {
        Map<String, Object> current = definition(code, version);
        if (!"DRAFT".equals(current.get("status")))
            throw new IllegalStateException("Only DRAFT contract definitions can be deleted");
        jdbc.update("DELETE FROM contract_definition WHERE contract_code=? AND contract_version=?",
                normalizeCode(code), version);
    }

    public Validation validate(String code, String version, JsonNode payload,
                               String bankCode, String sourceType, String sourceRef) {
        JsonNode schema = schema(code, version);
        List<String> errors = new ArrayList<>();
        validateNode(schema, payload, "$", errors);
        if (RISK_EXCHANGE.equals(normalizeCode(code)) && payload != null && payload.isObject()
                && payload.has("payload") && payload.has("payloadDigest")) {
            String actual = sha256(canonicalJson(payload.get("payload")));
            if (!actual.equalsIgnoreCase(payload.path("payloadDigest").asText()))
                errors.add("$.payloadDigest does not match the canonical payload hash");
        }
        String contentHash = sha256(canonicalJson(payload));
        jdbc.update("""
            INSERT INTO contract_validation_log
              (validation_id,contract_code,contract_version,bank_code,source_type,source_ref,
               content_sha256,valid,errors)
            VALUES (?,?,?,?,?,?,?,?,?::jsonb)
            """, "CVAL-" + UUID.randomUUID(), normalizeCode(code), version, bankCode,
                sourceType, sourceRef, contentHash, errors.isEmpty(), json(errors));
        return new Validation(errors.isEmpty(), errors, contentHash);
    }

    @Transactional
    public Map<String, Object> createInstance(String code, String version, String bankCode,
                                               String businessKey, JsonNode payload,
                                               String sourceType, String sourceRef, String username) {
        String normalizedCode = normalizeCode(code);
        requireActive(normalizedCode, version);
        Validation validation = validate(normalizedCode, version, payload, bankCode, sourceType, sourceRef);
        validation.requireValid();
        String instanceId = prefix(normalizedCode) + UUID.randomUUID();
        insertInstance(instanceId, 1, normalizedCode, version, bankCode, businessKey, payload,
                validation.contentHash(), "ACTIVE", sourceType, sourceRef, username);
        return instance(instanceId, null);
    }

    @Transactional
    public Map<String, Object> saveProjected(String code, String version, String bankCode,
                                              String businessKey, JsonNode payload,
                                              String sourceType, String sourceRef, String username) {
        String normalizedCode = normalizeCode(code);
        requireActive(normalizedCode, version);
        Validation validation = validate(normalizedCode, version, payload, bankCode, sourceType, sourceRef);
        validation.requireValid();
        List<Map<String, Object>> existing = jdbc.queryForList("""
            SELECT instance_id,revision,content_sha256 FROM contract_instance
            WHERE contract_code=? AND bank_code=? AND business_key=?
            ORDER BY revision DESC LIMIT 1
            """, normalizedCode, bankCode, businessKey);
        if (!existing.isEmpty()) {
            Map<String, Object> latest = existing.get(0);
            if (validation.contentHash().equals(Objects.toString(latest.get("content_sha256"))))
                return instance(Objects.toString(latest.get("instance_id")), ((Number) latest.get("revision")).intValue());
            String instanceId = Objects.toString(latest.get("instance_id"));
            int revision = ((Number) latest.get("revision")).intValue() + 1;
            insertInstance(instanceId, revision, normalizedCode, version, bankCode, businessKey, payload,
                    validation.contentHash(), "ACTIVE", sourceType, sourceRef, username);
            return instance(instanceId, revision);
        }
        return createInstance(normalizedCode, version, bankCode, businessKey, payload, sourceType, sourceRef, username);
    }

    @Transactional
    public Map<String, Object> updateInstance(String instanceId, JsonNode payload, String username) {
        Map<String, Object> latest = instance(instanceId, null);
        String code = Objects.toString(latest.get("contractCode"));
        String version = Objects.toString(latest.get("contractVersion"));
        String bank = Objects.toString(latest.get("bankCode"));
        Validation validation = validate(code, version, payload, bank, "CRUD_UPDATE", instanceId);
        validation.requireValid();
        int revision = ((Number) latest.get("revision")).intValue() + 1;
        insertInstance(instanceId, revision, code, version, bank,
                Objects.toString(latest.get("businessKey")), payload, validation.contentHash(),
                "ACTIVE", "CRUD_UPDATE", instanceId, username);
        return instance(instanceId, revision);
    }

    @Transactional
    public Map<String, Object> deleteInstance(String instanceId, String username) {
        Map<String, Object> latest = instance(instanceId, null);
        int revision = ((Number) latest.get("revision")).intValue() + 1;
        JsonNode payload = mapper.valueToTree(latest.get("payload"));
        insertInstance(instanceId, revision, Objects.toString(latest.get("contractCode")),
                Objects.toString(latest.get("contractVersion")), Objects.toString(latest.get("bankCode")),
                Objects.toString(latest.get("businessKey")), payload, Objects.toString(latest.get("contentSha256")),
                "DELETED", "CRUD_DELETE", instanceId, username);
        return instance(instanceId, revision);
    }

    public Map<String, Object> instance(String instanceId, Integer revision) {
        String sql = """
            SELECT instance_id AS "instanceId",revision,contract_code AS "contractCode",
                   contract_version AS "contractVersion",bank_code AS "bankCode",
                   business_key AS "businessKey",payload,content_sha256 AS "contentSha256",
                   validation_status AS "validationStatus",lifecycle_status AS "lifecycleStatus",
                   source_type AS "sourceType",source_ref AS "sourceRef",created_by AS "createdBy",
                   created_at AS "createdAt"
            FROM contract_instance WHERE instance_id=? %s ORDER BY revision DESC LIMIT 1
            """.formatted(revision == null ? "" : "AND revision=?");
        Map<String, Object> result = revision == null
                ? jdbc.queryForMap(sql, instanceId) : jdbc.queryForMap(sql, instanceId, revision);
        return jsonFields(result, "payload");
    }

    public List<Map<String, Object>> instances(String code, String bankCode, String businessKey, int limit) {
        return jdbc.queryForList("""
            SELECT DISTINCT ON (instance_id)
                   instance_id AS "instanceId",revision,contract_code AS "contractCode",
                   contract_version AS "contractVersion",bank_code AS "bankCode",
                   business_key AS "businessKey",content_sha256 AS "contentSha256",
                   lifecycle_status AS "lifecycleStatus",source_type AS "sourceType",
                   source_ref AS "sourceRef",created_at AS "createdAt"
            FROM contract_instance
            WHERE contract_code=? AND bank_code=?
              AND (CAST(? AS VARCHAR) IS NULL OR business_key=CAST(? AS VARCHAR))
            ORDER BY instance_id,revision DESC LIMIT ?
            """, normalizeCode(code), bankCode, businessKey, businessKey, Math.min(Math.max(limit, 1), 500));
    }

    public String semanticHash(JsonNode payload) {
        return sha256(canonicalJson(payload));
    }

    private void insertInstance(String instanceId, int revision, String code, String version,
                                String bankCode, String businessKey, JsonNode payload, String hash,
                                String lifecycle, String sourceType, String sourceRef, String username) {
        jdbc.update("""
            INSERT INTO contract_instance
              (instance_id,revision,contract_code,contract_version,bank_code,business_key,payload,
               content_sha256,validation_status,lifecycle_status,source_type,source_ref,created_by)
            VALUES (?,?,?,?,?,?,?::jsonb,?,'VALID',?,?,?,?)
            """, instanceId, revision, code, version, bankCode, businessKey, json(payload), hash,
                lifecycle, sourceType, sourceRef, username);
    }

    private JsonNode schema(String code, String version) {
        Object value = jdbc.queryForObject("""
            SELECT schema_json FROM contract_definition WHERE contract_code=? AND contract_version=?
            """, Object.class, normalizeCode(code), version);
        try {
            if (value instanceof org.postgresql.util.PGobject pg) return mapper.readTree(pg.getValue());
            return mapper.readTree(Objects.toString(value));
        } catch (Exception ex) { throw new IllegalStateException("Stored contract schema is invalid", ex); }
    }

    private void requireActive(String code, String version) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM contract_definition
            WHERE contract_code=? AND contract_version=? AND status='ACTIVE'
            """, Integer.class, code, version);
        if (count == null || count == 0) throw new IllegalStateException("Active contract version not found: " + code + "/" + version);
    }

    private void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.has("const") && !schema.get("const").equals(value))
            errors.add(path + " must equal " + schema.get("const"));
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode item : schema.path("enum")) if (item.equals(value)) found = true;
            if (!found) errors.add(path + " is not one of the allowed values");
        }
        if (schema.has("type") && !matchesType(schema.get("type"), value))
            errors.add(path + " has an invalid type");
        if (value != null && value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                String name = required.asText();
                if (!value.has(name) || value.get(name).isNull()) errors.add(path + "." + name + " is required");
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) properties.fields().forEachRemaining(entry -> {
                if (value.has(entry.getKey())) validateNode(entry.getValue(), value.get(entry.getKey()),
                        path + "." + entry.getKey(), errors);
            });
        }
        if (value != null && value.isArray() && schema.has("items")) {
            for (int index = 0; index < value.size(); index++)
                validateNode(schema.get("items"), value.get(index), path + "[" + index + "]", errors);
        }
        if (value != null && value.isTextual()) {
            if (schema.has("minLength") && value.textValue().length() < schema.path("minLength").asInt())
                errors.add(path + " is shorter than the minimum length");
            if (schema.has("pattern") && !Pattern.compile(schema.path("pattern").asText()).matcher(value.textValue()).matches())
                errors.add(path + " does not match the required pattern");
        }
        if (value != null && value.isNumber() && schema.has("minimum")
                && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)
            errors.add(path + " is below the minimum");
    }

    private boolean matchesType(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode option : type) if (matchesType(option, value)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value != null && value.isObject();
            case "array" -> value != null && value.isArray();
            case "string" -> value != null && value.isTextual();
            case "number", "integer" -> value != null && value.isNumber();
            case "boolean" -> value != null && value.isBoolean();
            case "null" -> value == null || value.isNull();
            default -> true;
        };
    }

    private String canonicalJson(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                String name = names.get(i);
                out.append(json(name)).append(':').append(canonicalJson(value.get(name)));
            }
            return out.append('}').toString();
        }
        if (value.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalJson(value.get(i)));
            }
            return out.append(']').toString();
        }
        if (value.isNumber()) return new BigDecimal(value.asText()).stripTrailingZeros().toPlainString();
        return value.toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Value cannot be serialized as JSON", ex); }
    }

    private Map<String, Object> jsonFields(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            Object value = row.get(field);
            try {
                if (value instanceof org.postgresql.util.PGobject pg) row.put(field, mapper.readTree(pg.getValue()));
                else if (value instanceof String string && (string.startsWith("{") || string.startsWith("[")))
                    row.put(field, mapper.readTree(string));
            } catch (Exception ex) { throw new IllegalStateException("Stored JSON is invalid: " + field, ex); }
        }
        return row;
    }

    private String normalizeCode(String code) {
        String normalized = Objects.toString(code, "").trim().toUpperCase().replace('-', '_');
        if (normalized.isBlank()) throw new IllegalArgumentException("contractCode is required");
        return normalized;
    }

    private String requiredText(Map<String, Object> body, String key) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String prefix(String code) {
        return switch (code) {
            case CASE_GRAPH -> "CGE-";
            case CANONICAL_EVENT -> "CEV-";
            case INFERENCE_EVIDENCE -> "IEV-C-";
            case RISK_EXCHANGE -> "RXE-";
            default -> "CI-";
        };
    }

    private record Builtin(String name, String resource) {}

    public record Validation(boolean valid, List<String> errors, String contentHash) {
        public void requireValid() {
            if (!valid) throw new IllegalArgumentException("Contract validation failed: " + String.join("; ", errors));
        }
    }
}
