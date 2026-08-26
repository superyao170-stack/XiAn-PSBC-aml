package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseKnowledgeExplanationServiceTest {
    private final CaseCoreChainService coreChainService = mock(CaseCoreChainService.class);
    private final CaseMatterExplanationService matterService =
            mock(CaseMatterExplanationService.class);
    private final CaseKnowledgeExplanationService service =
            new CaseKnowledgeExplanationService(
                    coreChainService, matterService, new ObjectMapper());

    @Test
    void buildsOneConsistentExplanationChainFromCoreKnowledgeReference() {
        when(coreChainService.graph("CASE-1")).thenReturn(Map.of(
                "nodes", List.of(
                        Map.of("id", "CASE-1::EVENT-1", "name", "跨境汇款事件"),
                        Map.of("id", "CASE-1::BPO-1", "name", "快速过渡行为"),
                        Map.of("id", "CASE-1::RISK-1", "name", "跨境转移风险")
                ),
                "edges", List.of(Map.of(
                        "id", "EDGE-1", "source", "CASE-1::EVENT-1",
                        "target", "CASE-1::BPO-1", "type", "SUPPORTS"
                )),
                "knowledgeRefs", List.of(Map.of(
                        "refId", "KREF-1", "definitionType", "TECHNIQUE",
                        "code", "T0121.001", "version", "1",
                        "definitionName", "跨境代理中介",
                        "ownerType", "RISK_HYPOTHESIS", "ownerId", "CASE-1::RISK-1",
                        "tactics", List.of(Map.of("code", "LAYERING", "name", "离析"))
                ))
        ));
        when(matterService.techniques("CASE-1")).thenReturn(List.of(Map.of(
                "occurrenceId", "TO-1",
                "techniqueCode", "T0121.001", "techniqueVersion", "1",
                "eventRefs", List.of("EVENT-1"),
                "indicatorResultRefs", List.of("CALC-1"),
                "behaviorOccurrenceRefs", List.of("BPO-1"),
                "riskEventRefs", List.of("RISK-1")
        )));
        when(matterService.reasoning("CASE-1")).thenReturn(Map.of(
                "indicatorResults", List.of(Map.of(
                        "calculationId", "CALC-1", "indicatorName", "跨境汇款指标",
                        "explanation", Map.of("eventRefs", List.of("EVENT-1")))),
                "behaviorPatternOccurrences", List.of(Map.of(
                        "occurrenceId", "BPO-1", "patternCode", "FAST_PASS_THROUGH")),
                "riskEvents", List.of(Map.of(
                        "riskEventId", "RISK-1", "title", "跨境转移风险"))
        ));
        when(matterService.matters("CASE-1")).thenReturn(List.of(Map.of(
                "matterId", "MATTER-1",
                "summary", "资金经多个账户继续转移",
                "eventRefs", List.of("EVENT-1"),
                "indicatorResultRefs", List.of(),
                "behaviorOccurrenceRefs", List.of(),
                "riskEventRefs", List.of()
        )));

        Map<String, Object> result = service.explanation("CASE-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains =
                (List<Map<String, Object>>) result.get("chains");
        assertThat(chains).hasSize(1);
        assertThat(chains.get(0))
                .containsEntry("fact", "跨境汇款事件")
                .containsEntry("indicator", "跨境汇款指标")
                .containsEntry("indicatorMode", "UNKNOWN")
                .containsEntry("pattern", "快速过渡行为")
                .containsEntry("risk", "跨境转移风险")
                .containsEntry("technique", "T0121.001 跨境代理中介")
                .containsEntry("tactic", "离析")
                .containsEntry("relation", "补强解释")
                .containsEntry("complete", true);
        assertThat(result.get("multiStageMatter")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> supplement =
                (Map<String, Object>) result.get("amltrixSupplement");
        assertThat(supplement)
                .containsEntry("status", "SUPPLEMENTED")
                .containsEntry("traditionalFactCount", 1)
                .containsEntry("amltrixChainCount", 1)
                .containsEntry("supplemented", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> graphNodes =
                (List<Map<String, Object>>) result.get("nodes");
        assertThat(graphNodes).extracting(item -> item.get("label"))
                .contains("INDICATORRESULT")
                .doesNotContain("TECHNIQUE", "TACTIC");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> graphEdges =
                (List<Map<String, Object>>) result.get("edges");
        assertThat(graphEdges).extracting(item -> item.get("type"))
                .doesNotContain("INDICATOR_MAPS_TECHNIQUE", "TECHNIQUE_BELONGS_TO_TACTIC");
        verify(coreChainService).completeBusinessIds("CASE-1", graphNodes, graphEdges);
    }

    @Test
    void separatesIndicatorBackedFactsFromAmltrixTacticChains() {
        when(coreChainService.graph("CASE-2")).thenReturn(Map.of(
                "nodes", List.of(Map.of(
                        "id", "CASE-2::EVENT-1", "name", "大额转账事件")),
                "edges", List.of(),
                "knowledgeRefs", List.of()
        ));
        when(matterService.techniques("CASE-2")).thenReturn(List.of());
        when(matterService.reasoning("CASE-2")).thenReturn(Map.of(
                "indicatorResults", List.of(Map.of(
                        "calculationId", "CALC-1", "indicatorName", "交易数量指标")),
                "behaviorPatternOccurrences", List.of(),
                "riskEvents", List.of()
        ));
        when(matterService.matters("CASE-2")).thenReturn(List.of(Map.of(
                "matterId", "MATTER-1",
                "summary", "核心账户向对手账户转移 592,571.00 USD",
                "certainty", "OBSERVED",
                "eventRefs", List.of("EVENT-1"),
                "indicatorResultRefs", List.of("CALC-1"),
                "behaviorOccurrenceRefs", List.of(),
                "riskEventRefs", List.of()
        )));

        Map<String, Object> result = service.explanation("CASE-2");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains =
                (List<Map<String, Object>>) result.get("chains");
        assertThat(result).containsEntry("matterCount", 1);
        assertThat(chains).isEmpty();
        assertThat(result.get("multiStageMatter")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> supplement =
                (Map<String, Object>) result.get("amltrixSupplement");
        assertThat(supplement)
                .containsEntry("status", "NO_AMLTRIX_HIT")
                .containsEntry("traditionalFactCount", 1)
                .containsEntry("amltrixChainCount", 0)
                .containsEntry("supplemented", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseFacts =
                (List<Map<String, Object>>) result.get("baseFactChains");
        assertThat(baseFacts).hasSize(1);
        assertThat(baseFacts.get(0))
                .containsEntry("chainType", "BASE_FACT")
                .containsEntry("complete", false)
                .containsEntry("fact", "大额转账事件及其来源证据")
                .containsEntry("indicator", "交易数量指标")
                .containsEntry("pattern", "尚未形成行为模式")
                .containsEntry("risk", "尚未形成风险假设")
                .containsEntry("technique", "尚未映射 AMLTRIX 技术")
                .containsEntry("tactic", "尚未归入 AMLTRIX 战术");
    }

    @Test
    void doesNotExposeKnowledgeReferenceWithoutCaseTechniqueOccurrence() {
        when(coreChainService.graph("CASE-3")).thenReturn(Map.of(
                "nodes", List.of(),
                "edges", List.of(),
                "knowledgeRefs", List.of(Map.of(
                        "refId", "KREF-3", "definitionType", "TECHNIQUE",
                        "code", "T0035", "version", "1",
                        "definitionName", "测试支付探测",
                        "tactics", List.of(Map.of("name", "操作规避"))
                ))
        ));
        when(matterService.techniques("CASE-3")).thenReturn(List.of());
        when(matterService.reasoning("CASE-3")).thenReturn(Map.of(
                "indicatorResults", List.of(),
                "behaviorPatternOccurrences", List.of(),
                "riskEvents", List.of()
        ));
        when(matterService.matters("CASE-3")).thenReturn(List.of());

        Map<String, Object> result = service.explanation("CASE-3");

        assertThat((List<?>) result.get("chains")).isEmpty();
        assertThat(result.get("multiStageMatter")).isNull();
    }
}
