package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IndicatorExpressionEvaluator {
    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    public Evaluation evaluate(JsonNode expression, Map<String, Object> features) {
        if (expression == null || !expression.isObject()) {
            throw new IllegalArgumentException("Indicator expression must be a JSON object");
        }
        String kind = expression.path("kind").asText("").toUpperCase();
        List<Map<String, Object>> contributions = new ArrayList<>();
        BigDecimal value = switch (kind) {
            case "FIELD" -> field(expression.path("path").asText(), features, contributions);
            case "RATIO" -> ratio(expression, features, contributions);
            case "WEIGHTED_SUM" -> weightedSum(expression.path("components"), features, contributions);
            case "COUNT" -> count(expression.path("path").asText(), features, contributions);
            case "THRESHOLD" -> threshold(expression, features, contributions);
            default -> throw new IllegalArgumentException("Unsupported indicator expression kind: " + kind);
        };
        return new Evaluation(value, contributions);
    }

    private BigDecimal field(String path, Map<String, Object> features,
                             List<Map<String, Object>> contributions) {
        BigDecimal value = decimal(resolve(features, path), path);
        contributions.add(contribution(path, BigDecimal.ONE, value, value));
        return value;
    }

    private BigDecimal ratio(JsonNode expression, Map<String, Object> features,
                             List<Map<String, Object>> contributions) {
        String numeratorPath = expression.path("numeratorPath").asText();
        String denominatorPath = expression.path("denominatorPath").asText();
        BigDecimal numerator = decimal(resolve(features, numeratorPath), numeratorPath);
        BigDecimal denominator = decimal(resolve(features, denominatorPath), denominatorPath);
        if (denominator.signum() == 0) throw new IllegalArgumentException("Ratio denominator cannot be zero");
        contributions.add(contribution(numeratorPath, BigDecimal.ONE, numerator, numerator));
        contributions.add(contribution(denominatorPath, BigDecimal.ONE, denominator, denominator));
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedSum(JsonNode components, Map<String, Object> features,
                                   List<Map<String, Object>> contributions) {
        if (!components.isArray() || components.isEmpty()) {
            throw new IllegalArgumentException("WEIGHTED_SUM requires components");
        }
        BigDecimal result = BigDecimal.ZERO;
        for (JsonNode component : components) {
            String path = component.path("path").asText();
            BigDecimal weight = component.has("weight")
                    ? component.path("weight").decimalValue() : BigDecimal.ONE;
            BigDecimal input = decimal(resolve(features, path), path);
            BigDecimal weighted = input.multiply(weight, MATH);
            contributions.add(contribution(path, weight, input, weighted));
            result = result.add(weighted, MATH);
        }
        return result;
    }

    private BigDecimal count(String path, Map<String, Object> features,
                             List<Map<String, Object>> contributions) {
        Object value = resolve(features, path);
        int count;
        if (value instanceof Iterable<?> iterable) {
            int current = 0;
            for (Object ignored : iterable) current++;
            count = current;
        } else if (value instanceof Map<?, ?> map) {
            count = map.size();
        } else if (value instanceof Object[] array) {
            count = array.length;
        } else {
            throw new IllegalArgumentException("COUNT path is not a collection: " + path);
        }
        BigDecimal result = BigDecimal.valueOf(count);
        contributions.add(contribution(path, BigDecimal.ONE, result, result));
        return result;
    }

    private BigDecimal threshold(JsonNode expression, Map<String, Object> features,
                                 List<Map<String, Object>> contributions) {
        String path = expression.path("path").asText();
        String operator = expression.path("operator").asText("GTE").toUpperCase();
        BigDecimal input = decimal(resolve(features, path), path);
        BigDecimal expected = expression.path("value").decimalValue();
        int comparison = input.compareTo(expected);
        boolean matched = switch (operator) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            case "EQ" -> comparison == 0;
            default -> throw new IllegalArgumentException("Unsupported threshold operator: " + operator);
        };
        BigDecimal result = matched ? BigDecimal.ONE : BigDecimal.ZERO;
        contributions.add(contribution(path, BigDecimal.ONE, input, result));
        return result;
    }

    private Object resolve(Map<String, Object> features, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Feature path is required");
        Object current = features;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else throw new IllegalArgumentException("Feature path does not exist: " + path);
            if (current == null) throw new IllegalArgumentException("Feature path does not exist: " + path);
        }
        return current;
    }

    private BigDecimal decimal(Object value, String path) {
        try {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number number) return new BigDecimal(number.toString());
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Feature is not numeric: " + path);
        }
    }

    private Map<String, Object> contribution(String path, BigDecimal weight,
                                             BigDecimal input, BigDecimal weighted) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put("weight", weight);
        item.put("input", input);
        item.put("contribution", weighted);
        return item;
    }

    public record Evaluation(BigDecimal value, List<Map<String, Object>> contributions) {}
}
