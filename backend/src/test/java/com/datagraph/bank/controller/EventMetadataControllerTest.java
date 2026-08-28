package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventMetadataControllerTest {
    @TempDir
    Path root;
    private final ObjectMapper json = new ObjectMapper();
    private Path amlPath;
    private Path fraudPath;
    private EventMetadataController controller;

    @BeforeEach
    void setUp() throws Exception {
        amlPath = root.resolve("aml.json");
        fraudPath = root.resolve("fraud.json");
        Files.writeString(amlPath, knowledgeBase("反洗钱风险事件知识库", "ET001", "大额转账"));
        Files.writeString(fraudPath, knowledgeBase("反欺诈风险事件知识库", "FRD-001", "异常登录"));
        controller = new EventMetadataController(json, amlPath, fraudPath);
    }

    @Test
    void readsTheKnowledgeBaseSelectedByScenario() throws Exception {
        CommonResult<Map<String, Object>> aml = controller.getEventMetadata("AML");
        CommonResult<Map<String, Object>> fraud = controller.getEventMetadata("ANTI_FRAUD");

        assertEquals(200, aml.getCode());
        assertEquals("ET001", firstId(aml));
        assertEquals("FRD-001", firstId(fraud));
        assertEquals("反欺诈", fraud.getData().get("scenarioName"));
    }

    @Test
    void createsUpdatesAndDeletesAnEventInTheSelectedKnowledgeBase() throws Exception {
        EventMetadataController.EventDefinition created = definition("FRD-002", "快进快出");
        assertEquals(2, controller.createEvent("ANTI_FRAUD", created).getData().get("count"));

        EventMetadataController.EventDefinition updated = definition("FRD-003", "多头分散转入");
        controller.updateEvent("ANTI_FRAUD", "FRD-002", updated);
        JsonNode afterUpdate = json.readTree(fraudPath.toFile());
        assertEquals("FRD-003", afterUpdate.path("event_types").path(1).path("id").asText());
        assertEquals(2, afterUpdate.path("metadata").path("event_count").asInt());

        controller.deleteEvent("ANTI_FRAUD", "FRD-003");
        JsonNode afterDelete = json.readTree(fraudPath.toFile());
        assertEquals(1, afterDelete.path("event_types").size());
        assertEquals("ET001", json.readTree(amlPath.toFile()).path("event_types").path(0).path("id").asText());
    }

    @Test
    void rejectsDuplicateEventIds() throws Exception {
        CommonResult<Map<String, Object>> result = controller.createEvent("AML", definition("ET001", "重复事件"));
        assertEquals(409, result.getCode());
    }

    private String firstId(CommonResult<Map<String, Object>> result) {
        List<?> eventTypes = (List<?>) result.getData().get("eventTypes");
        return String.valueOf(((Map<?, ?>) eventTypes.get(0)).get("id"));
    }

    private EventMetadataController.EventDefinition definition(String id, String name) {
        return new EventMetadataController.EventDefinition(id, name, "存在可核验行为", "测试分类");
    }

    private String knowledgeBase(String name, String id, String eventName) {
        return """
                {
                  "metadata":{"name":"%s","version":"1.0.0","event_count":1},
                  "event_types":[
                    {"id":"%s","name":"%s","rule":"存在明确行为","category":"测试分类"}
                  ]
                }
                """.formatted(name, id, eventName);
    }
}
