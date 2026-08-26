package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndicatorExecutionServiceTest {
    @Test
    void persistsEventTransactionAndEvidenceLineageForExecutableIndicator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "indicator_code", "BG_CASE_TRANSACTION_COUNT",
                "version", 1,
                "expression", "{\"kind\":\"FIELD\",\"path\":\"transactionCount\"}",
                "threshold_config", "{\"medium\":1,\"high\":3}")));
        ObjectMapper mapper = new ObjectMapper();
        IndicatorExecutionService service = new IndicatorExecutionService(
                jdbc, mapper, new IndicatorExpressionEvaluator());
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("transactionCount", 2);
        features.put("_provenance", Map.of(
                "transactionCount", Map.of(
                        "eventRefs", List.of("EVENT-1"),
                        "transactionRefs", List.of("TX-1", "TX-2"),
                        "evidenceRefs", List.of(Map.of("kind", "RECORD", "id", "TX-1")))));

        List<Map<String, Object>> results = service.execute(
                "BANK", "AML", "CASE", "CASE-1",
                List.of("BG_CASE_TRANSACTION_COUNT"), features);

        Map<?, ?> explanation = (Map<?, ?>) results.get(0).get("explanation");
        assertThat(explanation.get("lineagePaths"))
                .isEqualTo(List.of("transactionCount"));
        assertThat(explanation.get("eventRefs"))
                .isEqualTo(List.of("EVENT-1"));
        assertThat(explanation.get("transactionRefs"))
                .isEqualTo(List.of("TX-1", "TX-2"));
        assertThat((List<?>) explanation.get("evidenceRefs")).hasSize(1);
    }

    @Test
    void matchesFormalIndicatorsPerSemanticObservationInsteadOfGlobalTopN() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("indicator_code", "IND_HIGH", "indicator_name", "高频交易",
                        "description", "账户交易频繁且密集", "version", 1),
                Map.of("indicator_code", "IND_RAPID", "indicator_name", "资金过渡",
                        "description", "短时间内资金迅速转出且不留余额", "version", 1)));
        ObjectMapper mapper = new ObjectMapper();
        IndicatorExecutionService service = new IndicatorExecutionService(
                jdbc, mapper, mock(IndicatorExpressionEvaluator.class));
        ArrayNode observations = mapper.createArrayNode();
        observations.add(observation(mapper, "OBS_HIGH", "高频密集交易",
                "材料记载账户交易频繁且密集", "交易频繁"));
        observations.add(observation(mapper, "OBS_RAPID", "资金快进快出",
                "材料记载短时间内资金迅速转出且不留余额", "迅速转出"));

        List<Map<String, Object>> results = service.matchSemanticObservations(
                "BANK", "CASE", "CASE", "CASE-1", observations, "snapshot");

        assertThat(results).extracting(item -> item.get("indicatorCode"))
                .containsExactlyInAnyOrder("IND_HIGH", "IND_RAPID");
        assertThat(results).allSatisfy(item ->
                assertThat((List<?>) item.get("sourceObservationCodes")).hasSize(1));
    }

    private ObjectNode observation(ObjectMapper mapper, String code, String name,
                                   String explanation, String term) {
        ObjectNode observation = mapper.createObjectNode();
        observation.put("observationCode", code);
        observation.put("observationName", name);
        observation.put("explanation", explanation);
        observation.set("eventRefs", mapper.createArrayNode().add("EVENT-1"));
        observation.set("evidenceRefs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("term", term)
                        .put("quote", explanation)));
        return observation;
    }
}
