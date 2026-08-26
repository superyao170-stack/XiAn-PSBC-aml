package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.CaseCoreChainService;
import com.datagraph.bank.service.CaseKnowledgeExplanationService;
import com.datagraph.bank.service.CaseMatterExplanationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
public class CaseMatterController {
    private final CaseMatterExplanationService service;
    private final CaseCoreChainService coreChainService;
    private final CaseKnowledgeExplanationService knowledgeExplanationService;
    private final CurrentUser currentUser;

    public CaseMatterController(CaseMatterExplanationService service,
                                CaseCoreChainService coreChainService,
                                CaseKnowledgeExplanationService knowledgeExplanationService,
                                CurrentUser currentUser) {
        this.service = service;
        this.coreChainService = coreChainService;
        this.knowledgeExplanationService = knowledgeExplanationService;
        this.currentUser = currentUser;
    }

    @PostMapping("/{caseId}/matter-explanations/refresh")
    public CommonResult<Map<String, Object>> refresh(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        Map<String, Object> result = new java.util.LinkedHashMap<>(service.refresh(caseId));
        result.put("coreChain", coreChainService.snapshot(caseId));
        return CommonResult.success(result);
    }

    @GetMapping("/{caseId}/core-chains/current")
    public CommonResult<Map<String, Object>> currentCoreChain(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.graph(caseId));
    }

    @GetMapping("/{caseId}/knowledge-explanation-chains")
    public CommonResult<Map<String, Object>> knowledgeExplanationChains(
            @PathVariable String caseId,
            @RequestParam(defaultValue = "FULL") String view,
            @RequestParam(defaultValue = "220") int limit) {
        requireCaseAccess(caseId);
        return CommonResult.success("PANORAMA".equalsIgnoreCase(view)
                ? knowledgeExplanationService.panoramaExplanation(caseId, limit)
                : knowledgeExplanationService.explanation(caseId));
    }

    CommonResult<Map<String, Object>> knowledgeExplanationChains(String caseId) {
        return knowledgeExplanationChains(caseId, "FULL", 220);
    }

    @PostMapping("/{caseId}/core-chains/refresh")
    public CommonResult<Map<String, Object>> refreshCoreChain(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshot(caseId));
    }

    @GetMapping("/{caseId}/core-chains")
    public CommonResult<List<Map<String, Object>>> coreChains(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshots(caseId));
    }

    @GetMapping("/{caseId}/core-chains/{chainId}")
    public CommonResult<Map<String, Object>> coreChain(@PathVariable String caseId,
                                                       @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshotDetail(caseId, chainId));
    }

    @GetMapping("/{caseId}/core-chains/{chainId}/graph")
    public CommonResult<Map<String, Object>> coreChainGraph(@PathVariable String caseId,
                                                            @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshotGraph(caseId, chainId));
    }

    @GetMapping("/{caseId}/core-chains/{chainId}/claims")
    public CommonResult<List<Map<String, Object>>> coreChainClaims(@PathVariable String caseId,
                                                                   @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshotClaims(caseId, chainId));
    }

    @GetMapping("/{caseId}/core-chains/{chainId}/evidence")
    public CommonResult<Map<String, Object>> coreChainEvidence(@PathVariable String caseId,
                                                               @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshotEvidence(caseId, chainId));
    }

    @GetMapping("/{caseId}/core-chains/{chainId}/validate")
    public CommonResult<Map<String, Object>> validateCoreChain(@PathVariable String caseId,
                                                               @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.snapshotValidation(caseId, chainId));
    }

    @PostMapping("/{caseId}/core-chains/{chainId}/reproject")
    public CommonResult<Map<String, Object>> reprojectCoreChain(@PathVariable String caseId,
                                                                @PathVariable String chainId) {
        requireCaseAccess(caseId);
        return CommonResult.success(coreChainService.reproject(caseId, chainId));
    }

    @GetMapping("/{caseId}/matter-explanations")
    public CommonResult<List<Map<String, Object>>> matters(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(service.matters(caseId));
    }

    @GetMapping("/{caseId}/matter-explanations/{matterId}/evidence")
    public CommonResult<Map<String, Object>> evidence(@PathVariable String caseId,
                                                      @PathVariable String matterId) {
        requireCaseAccess(caseId);
        return CommonResult.success(service.matterEvidence(caseId, matterId));
    }

    @PutMapping("/{caseId}/matter-explanations/{matterId}/feedback")
    public CommonResult<Void> feedback(@PathVariable String caseId, @PathVariable String matterId,
                                       @RequestBody Map<String, Object> body) {
        requireCaseAccess(caseId);
        service.feedback(caseId, matterId, String.valueOf(body.get("feedback")),
                body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return CommonResult.success(null);
    }

    @GetMapping("/{caseId}/technique-occurrences")
    public CommonResult<List<Map<String, Object>>> techniques(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(service.techniques(caseId));
    }

    @GetMapping("/{caseId}/reasoning")
    public CommonResult<Map<String, Object>> reasoning(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(service.reasoning(caseId));
    }

    @PutMapping("/{caseId}/investigation-hypotheses/{hypothesisId}")
    public CommonResult<Void> updateInvestigationHypothesis(
            @PathVariable String caseId,
            @PathVariable String hypothesisId,
            @RequestBody Map<String, Object> body) {
        requireCaseAccess(caseId);
        service.updateInvestigationHypothesis(caseId, hypothesisId,
                String.valueOf(body.get("status")),
                body.get("resolution") == null ? null : String.valueOf(body.get("resolution")));
        return CommonResult.success(null);
    }

    @GetMapping("/{caseId}/review-suggestions")
    public CommonResult<List<Map<String, Object>>> suggestions(@PathVariable String caseId) {
        requireCaseAccess(caseId);
        return CommonResult.success(service.suggestions(caseId));
    }

    @PutMapping("/{caseId}/review-suggestions/{suggestionId}")
    public CommonResult<Void> updateSuggestion(@PathVariable String caseId,
                                               @PathVariable String suggestionId,
                                               @RequestBody Map<String, Object> body) {
        requireCaseAccess(caseId);
        service.updateSuggestion(caseId, suggestionId, String.valueOf(body.get("status")),
                body.get("resolution") == null ? null : String.valueOf(body.get("resolution")));
        return CommonResult.success(null);
    }

    private void requireCaseAccess(String caseId) {
        currentUser.requireAccessToBank(service.caseBankCode(caseId));
    }
}
