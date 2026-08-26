package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CaseCoreChainServiceBusinessIdTest {

    private final CaseCoreChainService service =
            new CaseCoreChainService(mock(JdbcTemplate.class), new ObjectMapper());

    @Test
    void preservesPersistedEventIdsAndCompletesPresentationIds() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("CASE-1", "CASE", null),
                // UUID/internal ordering deliberately conflicts with the
                // persisted business sequence.
                node("EVENT-Z", "EVENT", "41-EVT-001"),
                node("EVENT-A", "EVENT", "41-EVT-002"),
                node("CASE-1::ICALC-1", "INDICATOR_RESULT", null)
        ));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                edge("EDGE-Z", "EVENT-Z", "EVENT-A", "41-REL-009"),
                edge("DISPLAY-A", "EVENT-A", "CASE-1::ICALC-1", null)
        ));

        service.assignBusinessIds(41L, nodes, edges);

        assertThat(businessId(nodes, "EVENT-Z")).isEqualTo("41-EVT-001");
        assertThat(businessId(nodes, "EVENT-A")).isEqualTo("41-EVT-002");
        assertThat(businessId(nodes, "CASE-1::ICALC-1")).isEqualTo("41-IND-001");
        assertThat(businessId(edges, "EDGE-Z")).isEqualTo("41-REL-009");
        assertThat(businessId(edges, "DISPLAY-A")).isEqualTo("41-REL-010");
        assertThat(edgeProperties(edges, "DISPLAY-A"))
                .containsEntry("sourceBusinessId", "41-EVT-002")
                .containsEntry("targetBusinessId", "41-IND-001");

        // Rebuilding the explanation graph must not renumber any object.
        service.assignBusinessIds(41L, nodes, edges);
        assertThat(businessId(nodes, "EVENT-Z")).isEqualTo("41-EVT-001");
        assertThat(businessId(nodes, "EVENT-A")).isEqualTo("41-EVT-002");
        assertThat(businessId(edges, "DISPLAY-A")).isEqualTo("41-REL-010");
    }

    private Map<String, Object> node(String id, String canonicalType, String businessId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("canonicalType", canonicalType);
        if (businessId != null) properties.put("businessId", businessId);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("properties", properties);
        return node;
    }

    private Map<String, Object> edge(String id, String source, String target,
                                     String businessId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (businessId != null) properties.put("businessId", businessId);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("type", "TEST_RELATION");
        edge.put("properties", properties);
        return edge;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> edgeProperties(List<Map<String, Object>> edges, String id) {
        return (Map<String, Object>) edges.stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst().orElseThrow().get("properties");
    }

    @SuppressWarnings("unchecked")
    private String businessId(List<Map<String, Object>> items, String id) {
        Map<String, Object> item = items.stream()
                .filter(candidate -> id.equals(candidate.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> properties = (Map<String, Object>) item.get("properties");
        assertThat(item.get("businessId")).isEqualTo(properties.get("businessId"));
        return String.valueOf(properties.get("businessId"));
    }
}
