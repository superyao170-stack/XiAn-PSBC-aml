package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaseNamingPolicy {
    private static final Pattern CASE_NUMBER = Pattern.compile(
            "(?i)^(?:CASE[-_:])?[A-Z]{1,12}(?:[-_/][A-Z0-9]{1,16}){1,6}$");
    private static final Pattern CASE_NUMBER_IN_TEXT = Pattern.compile(
            "案件(?:编号|号)\\s*[：:]?\\s*[“\"']?([A-Za-z][A-Za-z0-9]*(?:[-_/][A-Za-z0-9]+)+)");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern RESPONSE_EVENT = Pattern.compile("冻结|止付|罚款|处罚|立案|判决|报送|调查");

    private CaseNamingPolicy() {
    }

    static Result normalize(String candidate, String sourceText, JsonNode graphOutput) {
        String original = text(candidate);
        String caseNo = CASE_NUMBER.matcher(original).matches() ? original : extractCaseNo(sourceText);
        if (isSemanticName(original)) {
            return new Result(original, caseNo);
        }

        String subject = firstName(graphOutput.path("nodes").path("customers"));
        List<String> events = eventNames(graphOutput.path("nodes").path("events"));
        String name;
        if (!events.isEmpty()) {
            String behavior = events.size() == 1
                    ? events.get(0)
                    : String.join("、", events.subList(0, events.size() - 1))
                    + "及" + events.get(events.size() - 1);
            name = subject.isBlank() ? behavior + "异常交易案" : subject + behavior + "案";
        } else {
            name = subject.isBlank() ? "待核查可疑交易案" : subject + "可疑交易案";
        }
        return new Result(name, caseNo);
    }

    static boolean isSemanticName(String value) {
        String normalized = text(value);
        return !normalized.isBlank()
                && CHINESE.matcher(normalized).find()
                && !CASE_NUMBER.matcher(normalized).matches();
    }

    private static String extractCaseNo(String sourceText) {
        Matcher matcher = CASE_NUMBER_IN_TEXT.matcher(text(sourceText));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstName(JsonNode nodes) {
        if (!nodes.isArray()) return "";
        for (JsonNode node : nodes) {
            String name = text(node.path("name").asText());
            if (!name.isBlank()) return name;
        }
        return "";
    }

    private static List<String> eventNames(JsonNode nodes) {
        List<String> result = new ArrayList<>();
        if (!nodes.isArray()) return result;
        for (JsonNode node : nodes) {
            String name = text(node.path("name").asText());
            if (name.isBlank() || RESPONSE_EVENT.matcher(name).find() || result.contains(name)) continue;
            result.add(name);
            if (result.size() == 3) break;
        }
        return result;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    record Result(String caseName, String sourceCaseNo) {
    }
}
