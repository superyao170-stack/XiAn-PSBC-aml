package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/system/event-metadata")
public class EventMetadataController {
    private static final String AML = "AML";
    private static final String ANTI_FRAUD = "ANTI_FRAUD";
    private static final String AML_KNOWLEDGE_BASE =
            "xian_modules/data/kb/risk_event_knowledge_base.json";
    private static final String FRAUD_KNOWLEDGE_BASE =
            "data/kb/fraud_risk_event_knowledge_base.json";
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final ObjectMapper objectMapper;
    private final Map<String, KnowledgeBase> knowledgeBases;

    @Autowired
    public EventMetadataController(
            ObjectMapper objectMapper,
            @Value("${worker.structured-case.root:../workers/structured-case-identification}")
            String structuredCaseWorkerRoot,
            @Value("${worker.anti-fraud-case.root:../workers/anti-fraud-case-identification}")
            String antiFraudWorkerRoot) {
        this(objectMapper,
                resolveWorkerRoot(structuredCaseWorkerRoot, "workers/structured-case-identification")
                        .resolve(AML_KNOWLEDGE_BASE).normalize(),
                resolveWorkerRoot(antiFraudWorkerRoot, "workers/anti-fraud-case-identification")
                        .resolve(FRAUD_KNOWLEDGE_BASE).normalize());
    }

    EventMetadataController(ObjectMapper objectMapper, Path amlPath, Path fraudPath) {
        this.objectMapper = objectMapper;
        this.knowledgeBases = Map.of(
                AML, new KnowledgeBase(AML, "反洗钱", AML_KNOWLEDGE_BASE, amlPath),
                ANTI_FRAUD, new KnowledgeBase(ANTI_FRAUD, "反欺诈", FRAUD_KNOWLEDGE_BASE, fraudPath));
    }

    @GetMapping
    public synchronized CommonResult<Map<String, Object>> getEventMetadata(
            @RequestParam(defaultValue = AML) String scenarioCode) throws Exception {
        KnowledgeBase knowledgeBase = knowledgeBase(scenarioCode);
        if (knowledgeBase == null) return CommonResult.error(400, "场景必须为 AML 或 ANTI_FRAUD");
        JsonNode root = read(knowledgeBase);
        if (root == null) return CommonResult.error(404, "事件知识库文件不存在");
        if (!root.path("event_types").isArray()) {
            return CommonResult.error(500, "事件知识库缺少 event_types 数组");
        }
        return CommonResult.success(response(knowledgeBase, root));
    }

    @PostMapping
    public synchronized CommonResult<Map<String, Object>> createEvent(
            @RequestParam(defaultValue = AML) String scenarioCode,
            @RequestBody EventDefinition input) throws Exception {
        KnowledgeBase knowledgeBase = knowledgeBase(scenarioCode);
        if (knowledgeBase == null) return CommonResult.error(400, "场景必须为 AML 或 ANTI_FRAUD");
        String validation = validate(input);
        if (validation != null) return CommonResult.error(400, validation);
        ObjectNode root = readObject(knowledgeBase);
        if (root == null) return CommonResult.error(404, "事件知识库文件不存在");
        ArrayNode events = eventTypes(root);
        if (findIndex(events, input.id()) >= 0) return CommonResult.error(409, "事件编号已存在");
        events.add(toNode(input));
        write(knowledgeBase, root);
        return CommonResult.success(response(knowledgeBase, root));
    }

    @PutMapping("/{eventId}")
    public synchronized CommonResult<Map<String, Object>> updateEvent(
            @RequestParam(defaultValue = AML) String scenarioCode,
            @PathVariable String eventId,
            @RequestBody EventDefinition input) throws Exception {
        KnowledgeBase knowledgeBase = knowledgeBase(scenarioCode);
        if (knowledgeBase == null) return CommonResult.error(400, "场景必须为 AML 或 ANTI_FRAUD");
        String validation = validate(input);
        if (validation != null) return CommonResult.error(400, validation);
        ObjectNode root = readObject(knowledgeBase);
        if (root == null) return CommonResult.error(404, "事件知识库文件不存在");
        ArrayNode events = eventTypes(root);
        int index = findIndex(events, eventId);
        if (index < 0) return CommonResult.error(404, "事件定义不存在");
        int duplicateIndex = findIndex(events, input.id());
        if (duplicateIndex >= 0 && duplicateIndex != index) return CommonResult.error(409, "事件编号已存在");
        events.set(index, toNode(input));
        write(knowledgeBase, root);
        return CommonResult.success(response(knowledgeBase, root));
    }

    @DeleteMapping("/{eventId}")
    public synchronized CommonResult<Map<String, Object>> deleteEvent(
            @RequestParam(defaultValue = AML) String scenarioCode,
            @PathVariable String eventId) throws Exception {
        KnowledgeBase knowledgeBase = knowledgeBase(scenarioCode);
        if (knowledgeBase == null) return CommonResult.error(400, "场景必须为 AML 或 ANTI_FRAUD");
        ObjectNode root = readObject(knowledgeBase);
        if (root == null) return CommonResult.error(404, "事件知识库文件不存在");
        ArrayNode events = eventTypes(root);
        int index = findIndex(events, eventId);
        if (index < 0) return CommonResult.error(404, "事件定义不存在");
        events.remove(index);
        write(knowledgeBase, root);
        return CommonResult.success(response(knowledgeBase, root));
    }

    private JsonNode read(KnowledgeBase knowledgeBase) throws Exception {
        return Files.isRegularFile(knowledgeBase.path())
                ? objectMapper.readTree(knowledgeBase.path().toFile()) : null;
    }

    private ObjectNode readObject(KnowledgeBase knowledgeBase) throws Exception {
        JsonNode root = read(knowledgeBase);
        if (root == null) return null;
        if (!root.isObject()) throw new IllegalStateException("事件知识库根节点必须为对象");
        return (ObjectNode) root;
    }

    private ArrayNode eventTypes(ObjectNode root) {
        JsonNode events = root.get("event_types");
        if (events == null) return root.putArray("event_types");
        if (!events.isArray()) throw new IllegalStateException("事件知识库缺少 event_types 数组");
        return (ArrayNode) events;
    }

    private void write(KnowledgeBase knowledgeBase, ObjectNode root) throws Exception {
        ArrayNode events = eventTypes(root);
        ObjectNode metadata = root.with("metadata");
        metadata.put("event_count", events.size());
        metadata.put("updated", LocalDate.now().toString());
        Path parent = knowledgeBase.path().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, knowledgeBase.path().getFileName().toString(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, knowledgeBase.path(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, knowledgeBase.path(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> response(KnowledgeBase knowledgeBase, JsonNode root) {
        List<Map<String, String>> rows = objectMapper.convertValue(
                root.path("event_types"),
                objectMapper.getTypeFactory().constructCollectionType(List.class,
                        objectMapper.getTypeFactory().constructMapType(
                                LinkedHashMap.class, String.class, String.class)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioCode", knowledgeBase.scenarioCode());
        result.put("scenarioName", knowledgeBase.scenarioName());
        result.put("metadata", objectMapper.convertValue(root.path("metadata"), Map.class));
        result.put("eventTypes", rows);
        result.put("count", rows.size());
        result.put("source", knowledgeBase.relativePath());
        return result;
    }

    private ObjectNode toNode(EventDefinition input) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", input.id().trim());
        node.put("name", input.name().trim());
        node.put("rule", input.rule().trim());
        node.put("category", input.category().trim());
        return node;
    }

    private int findIndex(ArrayNode events, String eventId) {
        if (eventId == null) return -1;
        for (int index = 0; index < events.size(); index++) {
            if (eventId.equals(events.get(index).path("id").asText())) return index;
        }
        return -1;
    }

    private String validate(EventDefinition input) {
        if (input == null) return "事件定义不能为空";
        if (input.id() == null || !EVENT_ID.matcher(input.id().trim()).matches()) {
            return "事件编号只能包含字母、数字、点、下划线和短横线，且长度不超过 64";
        }
        if (blank(input.name())) return "事件名称不能为空";
        if (blank(input.category())) return "业务分类不能为空";
        if (blank(input.rule())) return "识别规则不能为空";
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private KnowledgeBase knowledgeBase(String scenarioCode) {
        return knowledgeBases.get(scenarioCode == null ? "" : scenarioCode.trim().toUpperCase());
    }

    private static Path resolveWorkerRoot(String configured, String projectRelative) {
        Path requested = Path.of(configured);
        if (requested.isAbsolute()) return requested.normalize();
        Path applicationRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : List.of(applicationRoot.resolve(requested),
                applicationRoot.resolve(projectRelative), applicationRoot.resolve("../" + projectRelative))) {
            if (Files.exists(candidate)) return candidate.normalize();
        }
        return applicationRoot.resolve(requested).normalize();
    }

    public record EventDefinition(String id, String name, String rule, String category) {}
    private record KnowledgeBase(String scenarioCode, String scenarioName, String relativePath, Path path) {}
}
