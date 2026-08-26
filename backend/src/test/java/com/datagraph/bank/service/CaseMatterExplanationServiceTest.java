package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseMatterExplanationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseMatterExplanationService service =
            new CaseMatterExplanationService(
                    mock(JdbcTemplate.class), mapper,
                    mock(RiskAnalyticsGateway.class),
                    mock(TuGraphStructuredWriter.class),
                    mock(IndicatorExecutionService.class));

    @Test
    void combinesSeveralIndicatorHitsToSatisfyPatternObservationContract() {
        ObjectNode result = resultWithPattern("OBS_A", "OBS_B");
        List<Map<String, Object>> indicators = List.of(
                Map.of("indicatorCode", "IND_A", "calculationId", "CALC_A",
                        "sourceObservationCodes", List.of("OBS_A")),
                Map.of("indicatorCode", "IND_B", "calculationId", "CALC_B",
                        "sourceObservationCodes", List.of("OBS_B")));

        ReflectionTestUtils.invokeMethod(
                service, "attachIndicatorResultRefs", result, indicators);

        var refs = result.path("behaviorPatternOccurrences").get(0)
                .path("indicatorResultRefs");
        assertThat(refs.size()).isEqualTo(2);
        assertThat(refs.get(0).asText()).isEqualTo("CALC_A");
        assertThat(refs.get(1).asText()).isEqualTo("CALC_B");
    }

    @Test
    void rejectsPartialObservationCoverage() {
        ObjectNode result = resultWithPattern("OBS_A", "OBS_B");
        List<Map<String, Object>> indicators = List.of(
                Map.of("indicatorCode", "IND_A", "calculationId", "CALC_A",
                        "sourceObservationCodes", List.of("OBS_A")));

        ReflectionTestUtils.invokeMethod(
                service, "attachIndicatorResultRefs", result, indicators);

        assertThat(result.path("behaviorPatternOccurrences").get(0)
                .path("indicatorResultRefs")).isEmpty();
    }

    @Test
    void linksMatterToTechniqueThroughSharedIndicatorAndRiskChain() {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode pattern = mapper.createObjectNode()
                .put("occurrenceId", "PATTERN-1");
        pattern.set("eventRefs", mapper.createArrayNode().add("EVENT-1"));
        result.set("behaviorPatternOccurrences",
                mapper.createArrayNode().add(pattern));
        ObjectNode risk = mapper.createObjectNode()
                .put("riskEventId", "RISK-1");
        risk.set("behaviorOccurrenceRefs",
                mapper.createArrayNode().add("PATTERN-1"));
        result.set("riskEvents", mapper.createArrayNode().add(risk));
        ObjectNode technique = mapper.createObjectNode()
                .put("techniqueCode", "T0001");
        technique.set("indicatorResultRefs",
                mapper.createArrayNode().add("CALC-1"));
        technique.set("behaviorOccurrenceRefs",
                mapper.createArrayNode().add("PATTERN-1"));
        technique.set("riskEventRefs", mapper.createArrayNode().add("RISK-1"));
        result.set("techniqueOccurrences",
                mapper.createArrayNode().add(technique));
        ObjectNode matter = mapper.createObjectNode();
        matter.set("eventRefs", mapper.createArrayNode().add("EVENT-1"));
        matter.set("indicatorResultRefs",
                mapper.createArrayNode().add("CALC-1"));
        result.set("matters", mapper.createArrayNode().add(matter));

        ReflectionTestUtils.invokeMethod(service, "attachCoreReasoningRefs", result);

        assertThat(result.path("matters").get(0)
                .path("techniqueCodes").get(0).asText()).isEqualTo("T0001");
    }

    @Test
    void techniqueOnlyAssociatesPatternsWhoseObservationContractIsCovered() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CaseMatterExplanationService localService =
                new CaseMatterExplanationService(
                        jdbc, mapper, mock(RiskAnalyticsGateway.class),
                        mock(TuGraphStructuredWriter.class),
                        mock(IndicatorExecutionService.class));
        when(jdbc.queryForList(anyString(), eq("IND-1"))).thenReturn(List.of(Map.of(
                "source_code", "T0035", "source_version", "1",
                "target_code", "IND-1")));
        ObjectNode result = mapper.createObjectNode();
        ArrayNode patterns = mapper.createArrayNode();
        patterns.add(patternWithContract("PATTERN-A", "CALC-1", "OBS_A"));
        patterns.add(patternWithContract("PATTERN-AB", "CALC-1", "OBS_A", "OBS_B"));
        result.set("behaviorPatternOccurrences", patterns);
        ArrayNode risks = mapper.createArrayNode();
        risks.add(riskForPattern("RISK-A", "PATTERN-A"));
        risks.add(riskForPattern("RISK-AB", "PATTERN-AB"));
        result.set("riskEvents", risks);

        ReflectionTestUtils.invokeMethod(localService, "rebuildTechniqueOccurrences",
                "CASE-1", result, List.of(Map.of(
                        "indicatorCode", "IND-1",
                        "calculationId", "CALC-1",
                        "semanticScore", 0.9,
                        "sourceObservationCodes", List.of("OBS_A"),
                        "explanation", Map.of("eventRefs", List.of("EVENT-1")))),
                List.of(Map.of("eventId", "EVENT-1", "confidence", 0.8)));

        JsonNode technique = result.path("techniqueOccurrences").get(0);
        assertThat(technique.path("behaviorOccurrenceRefs"))
                .extracting(JsonNode::asText)
                .containsExactly("PATTERN-A");
        assertThat(technique.path("riskEventRefs"))
                .extracting(JsonNode::asText)
                .containsExactly("RISK-A");
    }

    private ObjectNode resultWithPattern(String... observationCodes) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode pattern = mapper.createObjectNode();
        pattern.set("indicatorResultRefs", mapper.createArrayNode());
        pattern.set("indicatorCodes", mapper.createArrayNode());
        ArrayNode observations = mapper.createArrayNode();
        for (String code : observationCodes) observations.add(code);
        pattern.set("observationCodes", observations);
        result.set("behaviorPatternOccurrences",
                mapper.createArrayNode().add(pattern));
        result.set("matters", mapper.createArrayNode());
        result.set("techniqueOccurrences", mapper.createArrayNode());
        return result;
    }

    private ObjectNode patternWithContract(
            String occurrenceId, String calculationId, String... constraints) {
        ObjectNode pattern = mapper.createObjectNode().put("occurrenceId", occurrenceId);
        pattern.set("indicatorResultRefs", mapper.createArrayNode().add(calculationId));
        ArrayNode matched = mapper.createArrayNode();
        for (String constraint : constraints) {
            matched.add(mapper.createObjectNode()
                    .put("constraint", constraint).put("required", true));
        }
        pattern.set("matchedConstraints", matched);
        return pattern;
    }

    private ObjectNode riskForPattern(String riskId, String patternId) {
        ObjectNode risk = mapper.createObjectNode().put("riskEventId", riskId);
        risk.set("behaviorOccurrenceRefs", mapper.createArrayNode().add(patternId));
        return risk;
    }
}
