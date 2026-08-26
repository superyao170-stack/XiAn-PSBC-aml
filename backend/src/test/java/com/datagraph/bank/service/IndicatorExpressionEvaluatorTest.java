package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndicatorExpressionEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IndicatorExpressionEvaluator evaluator = new IndicatorExpressionEvaluator();

    @Test
    void weightedSumReturnsValueAndContributions() throws Exception {
        var expression = mapper.readTree("""
            {"kind":"WEIGHTED_SUM","components":[
              {"path":"fund.amountRisk","weight":0.7},
              {"path":"time.nightRisk","weight":0.3}
            ]}
            """);
        var result = evaluator.evaluate(expression, Map.of(
                "fund", Map.of("amountRisk", 1),
                "time", Map.of("nightRisk", new BigDecimal("0.5"))));
        assertEquals(0, new BigDecimal("0.85").compareTo(result.value()));
        assertEquals(2, result.contributions().size());
    }

    @Test
    void ratioRejectsZeroDenominator() throws Exception {
        var expression = mapper.readTree("""
            {"kind":"RATIO","numeratorPath":"out","denominatorPath":"total"}
            """);
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(expression, Map.of("out", 1, "total", 0)));
    }

    @Test
    void countSupportsLists() throws Exception {
        var expression = mapper.readTree("{" + "\"kind\":\"COUNT\",\"path\":\"events\"}");
        var result = evaluator.evaluate(expression, Map.of("events", List.of("A", "B", "C")));
        assertEquals(new BigDecimal("3"), result.value());
    }
}
