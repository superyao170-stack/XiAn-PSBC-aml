package com.datagraph.bank.controller;

import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.CaseCoreChainService;
import com.datagraph.bank.service.CaseKnowledgeExplanationService;
import com.datagraph.bank.service.CaseMatterExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CaseMatterControllerTest {
    private CaseMatterExplanationService service;
    private CaseCoreChainService coreChainService;
    private CaseKnowledgeExplanationService knowledgeExplanationService;
    private CurrentUser currentUser;
    private CaseMatterController controller;

    @BeforeEach
    void setUp() {
        service = mock(CaseMatterExplanationService.class);
        coreChainService = mock(CaseCoreChainService.class);
        knowledgeExplanationService = mock(CaseKnowledgeExplanationService.class);
        currentUser = mock(CurrentUser.class);
        controller = new CaseMatterController(
                service, coreChainService, knowledgeExplanationService, currentUser);
        when(service.caseBankCode("CASE-1")).thenReturn("BANK001");
    }

    @Test
    void mattersChecksCaseBankAndReturnsTraceableExplanations() {
        when(service.matters("CASE-1")).thenReturn(List.of(Map.of(
                "matterId", "MATTER-1",
                "summary", "短时间内发生关联资金动作",
                "eventRefs", List.of("EVENT-1")
        )));

        var result = controller.matters("CASE-1");

        assertEquals(200, result.getCode());
        assertEquals("MATTER-1", result.getData().get(0).get("matterId"));
        verify(currentUser).requireAccessToBank("BANK001");
        verify(service).matters("CASE-1");
    }

    @Test
    void evidenceKeepsEventDefinitionBindingInResponse() {
        when(service.matterEvidence("CASE-1", "MATTER-1")).thenReturn(Map.of(
                "matterId", "MATTER-1",
                "events", List.of(Map.of(
                        "eventId", "EVENT-1",
                        "eventFrameCode", "STRUCTURED_TRANSFER",
                        "eventFrameVersion", 1,
                        "definitionBindingStatus", "BOUND"
                ))
        ));

        var result = controller.evidence("CASE-1", "MATTER-1");
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) result.getData().get("events");

        assertEquals("STRUCTURED_TRANSFER", events.get(0).get("eventFrameCode"));
        assertEquals("BOUND", events.get(0).get("definitionBindingStatus"));
        verify(currentUser).requireAccessToBank("BANK001");
    }

    @Test
    void currentCoreChainReturnsVersionedSchemaAndChecksAccess() {
        when(coreChainService.graph("CASE-1")).thenReturn(Map.of(
                "caseId", "CASE-1",
                "summary", Map.of("schemaVersion", "1.8", "formalNodeTypeCount", 15)
        ));

        var result = controller.currentCoreChain("CASE-1");

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) result.getData().get("summary");
        assertEquals("1.8", summary.get("schemaVersion"));
        assertEquals(15, summary.get("formalNodeTypeCount"));
        verify(currentUser).requireAccessToBank("BANK001");
        verify(coreChainService).graph("CASE-1");
    }

    @Test
    void knowledgeExplanationChainsUseUnifiedServiceAndCheckAccess() {
        when(knowledgeExplanationService.explanation("CASE-1")).thenReturn(Map.of(
                "caseId", "CASE-1",
                "chains", List.of(Map.of("key", "KREF-1", "technique", "T0001 技术"))
        ));

        var result = controller.knowledgeExplanationChains("CASE-1");

        @SuppressWarnings("unchecked")
        var chains = (List<Map<String, Object>>) result.getData().get("chains");
        assertEquals("T0001 技术", chains.get(0).get("technique"));
        verify(currentUser).requireAccessToBank("BANK001");
        verify(knowledgeExplanationService).explanation("CASE-1");
    }
}
