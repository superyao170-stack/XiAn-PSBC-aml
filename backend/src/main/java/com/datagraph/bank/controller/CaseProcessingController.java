package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.CaseProcessingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/case-processing")
public class CaseProcessingController {
    private static final Set<String> STAGES = Set.of("PENDING_REPORT","PENDING_EXTRACTION","PENDING_SIMILARITY","PENDING_APPROVAL");
    private final CaseProcessingService service;
    private final CurrentUser currentUser;

    public CaseProcessingController(CaseProcessingService service, CurrentUser currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    @GetMapping
    public CommonResult<Map<String,Object>> page(
            @RequestParam String stage,
            @RequestParam(required=false) String recognitionMode,
            @RequestParam(required=false) String scenarioCode,
            @RequestParam(required=false) String caseId,
            @RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="10") int pageSize) {
        if (!STAGES.contains(stage)) return CommonResult.error(400, "不支持的案例池状态");
        String bank = currentUser.isBankAdmin() ? currentUser.requiredBankCode() : null;
        return CommonResult.success(service.page(stage, recognitionMode, scenarioCode, caseId, bank, pageNum, pageSize));
    }

    @GetMapping("/{caseId}")
    public CommonResult<Map<String,Object>> detail(@PathVariable String caseId) throws Exception {
        Map<String,Object> result = service.detail(caseId);
        currentUser.requireAccessToBank(String.valueOf(result.get("bankCode")));
        return CommonResult.success(result);
    }

    @GetMapping("/similarity-graph/approved")
    public CommonResult<List<Map<String,Object>>> approvedSimilarityGraph() {
        String bank = currentUser.isBankAdmin() ? currentUser.requiredBankCode() : null;
        return CommonResult.success(service.approvedSimilarityGraph(bank));
    }

    @PostMapping("/actions/{action}")
    public CommonResult<Map<String,Object>> process(@PathVariable String action, @RequestBody BatchRequest request) throws Exception {
        String normalized = action.trim().toUpperCase();
        if (!Set.of("REPORT","FRAMEWORK","SIMILARITY").contains(normalized)) return CommonResult.error(400, "不支持的处理动作");
        try {
            requireCaseAccess(request.caseIds());
            return CommonResult.success(service.process(request.caseIds(), normalized, currentUser.username()));
        }
        catch (IllegalArgumentException ex) { return CommonResult.error(400, ex.getMessage()); }
    }

    @PutMapping("/{caseId}/report")
    public CommonResult<Void> updateReport(@PathVariable String caseId, @RequestBody ReportUpdate request) throws Exception {
        requireCaseAccess(List.of(caseId));
        service.updateReport(caseId, request.analysisText(), currentUser.username());
        return CommonResult.success(null);
    }

    @PutMapping("/{caseId}/approve")
    public CommonResult<Void> approve(@PathVariable String caseId, @RequestBody ApprovalRequest request) throws Exception {
        requireCaseAccess(List.of(caseId));
        service.approve(caseId, request.riskLevel(), request.orderedSimilarCaseIds(), currentUser.username());
        return CommonResult.success(null);
    }

    public record BatchRequest(List<String> caseIds) {}
    public record ReportUpdate(String analysisText) {}
    public record ApprovalRequest(String riskLevel, List<String> orderedSimilarCaseIds) {}

    private void requireCaseAccess(List<String> caseIds) throws Exception {
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个案例");
        for (String caseId : caseIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            Map<String,Object> detail = service.detail(caseId);
            currentUser.requireAccessToBank(String.valueOf(detail.get("bankCode")));
        }
    }
}
