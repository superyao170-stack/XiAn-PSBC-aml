package com.datagraph.bank.util;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EvidenceTitleNormalizer {
    private static final Pattern GENERIC = Pattern.compile(
            "^(?:材料)?(?:事实)?证据\\s*[一二三四五六七八九十0-9]*$");

    private EvidenceTitleNormalizer() {}

    public static String meaningfulTitle(Object rawName, Object rawSummary) {
        String name = Objects.toString(rawName, "").trim();
        if (!name.isBlank() && !GENERIC.matcher(name).matches()) return name;
        String summary = Objects.toString(rawSummary, "").trim();
        List<Map.Entry<List<String>, String>> rules = List.of(
                Map.entry(List.of("他人银行卡", "他人账户", "借用银行卡", "提供银行卡"),
                        "使用他人银行卡或账户参与资金流转"),
                Map.entry(List.of("取现后", "提取现金后", "现金交付"),
                        "取现及现金后续转移"),
                Map.entry(List.of("指定账户", "指定收款账户", "收款码"),
                        "资金转入指定收款渠道"),
                Map.entry(List.of("金条", "黄金", "贵金属"),
                        "资金转换为贵金属"),
                Map.entry(List.of("USDT", "虚拟货币", "加密货币"),
                        "资金转换为数字资产"),
                Map.entry(List.of("冻结", "止付"), "涉案账户冻结止付"),
                Map.entry(List.of("合同", "发票"), "交易合同或票据材料")
        );
        for (Map.Entry<List<String>, String> rule : rules) {
            if (rule.getKey().stream().anyMatch(summary::contains)) {
                return rule.getValue();
            }
        }
        String cleaned = summary.replaceFirst(
                "^(?:经审理查明|经查|上述事实|证据证实|判决认定)[，,:：\\s]*", "")
                .replaceAll("^[，,。；;：:\\s]+|[，,。；;：:\\s]+$", "");
        if (cleaned.isBlank()) return "文本事实证据";
        return cleaned.length() > 24 ? cleaned.substring(0, 24) + "…" : cleaned;
    }

    @SuppressWarnings("unchecked")
    public static void normalizeWorkspace(Map<String, Object> workspace) {
        Object rawNodes = workspace.get("nodes");
        if (!(rawNodes instanceof Map<?, ?> nodes)) return;
        Object rawEvidences = nodes.get("evidences");
        if (!(rawEvidences instanceof List<?> evidences)) return;
        for (Object rawEvidence : evidences) {
            if (!(rawEvidence instanceof Map<?, ?> evidence)) continue;
            Map<String, Object> mutable = (Map<String, Object>) evidence;
            mutable.put("name", meaningfulTitle(
                    mutable.get("name"),
                    mutable.getOrDefault("summary", mutable.get("source"))));
        }
    }
}
