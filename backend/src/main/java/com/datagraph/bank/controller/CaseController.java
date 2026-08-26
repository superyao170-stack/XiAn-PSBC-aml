
package com.datagraph.bank.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.common.response.PageResult;
import com.datagraph.bank.config.OpenApiConfig;
import com.datagraph.bank.entity.CfRiskCase;
import com.datagraph.bank.entity.CfRiskEvent;
import com.datagraph.bank.entity.RiskSignal;
import com.datagraph.bank.mapper.CfRiskCaseMapper;
import com.datagraph.bank.mapper.CfRiskEventMapper;
import com.datagraph.bank.mapper.RiskSignalMapper;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.TuGraphStructuredWriter;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.datagraph.bank.util.EvidenceTitleNormalizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/cases")
@CrossOrigin(origins = "*")
public class CaseController {
    private static final List<Set<String>> MATTER_FACT_GROUPS = List.of(
            Set.of("THRESHOLD_STRUCTURING", "THRESHOLD_AMOUNT_ACTIVITY", "ROUND_AMOUNT_STRUCTURING"),
            Set.of("CROSS_BORDER_MULTI_ACCOUNT_TRANSFER", "MULTI_ACCOUNT_TRANSFER", "ACCOUNT_MULE_USAGE",
                    "HIGH_FREQUENCY_PASS_THROUGH", "MULTI_ACCOUNT_LAYERING"),
            Set.of("CASH_DEPOSIT", "CASH_WITHDRAWAL", "CASH_DELIVERY"),
            Set.of("PRECIOUS_METAL_CONVERSION", "DIGITAL_ASSET_CONVERSION", "DEFI_ACTIVITY",
                    "CROSS_CHAIN_TRANSFER"),
            Set.of("ORGANIZED_ROLE_ARRANGEMENT", "OPERATIONAL_EVASION"),
            Set.of("FICTITIOUS_TRADE", "SERVICE_FEE_JUSTIFICATION", "LOAN_JUSTIFICATION",
                    "LEGITIMATE_ASSET_INVESTMENT")
    );

    private final CfRiskCaseMapper caseMapper;
    private final CfRiskEventMapper eventMapper;
    private final RiskSignalMapper signalMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final TuGraphStructuredWriter graphWriter;

    @Autowired
    public CaseController(CfRiskCaseMapper caseMapper, CfRiskEventMapper eventMapper,
                          RiskSignalMapper signalMapper, JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                          ObjectMapper objectMapper, TuGraphStructuredWriter graphWriter) {
        this.caseMapper = caseMapper;
        this.eventMapper = eventMapper;
        this.signalMapper = signalMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.graphWriter = graphWriter;
    }

    public CaseController(CfRiskCaseMapper caseMapper, CfRiskEventMapper eventMapper,
                          RiskSignalMapper signalMapper, JdbcTemplate jdbcTemplate, CurrentUser currentUser) {
        this(caseMapper, eventMapper, signalMapper, jdbcTemplate, currentUser, new ObjectMapper(), null);
    }

    @GetMapping
    @Operation(
            summary = "1. 查询案例列表",
            description = "分页查询案例。复核工作台使用 caseStatus=IN_REVIEW，审批工作台使用 caseStatus=PENDING_APPROVAL。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    public CommonResult<PageResult<CfRiskCase>> getCases(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String caseStatus,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String recognitionMode,
            @RequestParam(required = false) String bankCode,
            @RequestParam(required = false) String jobId) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        Page<CfRiskCase> page = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<CfRiskCase> wrapper = new LambdaQueryWrapper<CfRiskCase>()
                .eq(CfRiskCase::getDeleted, false)
                .orderByDesc(CfRiskCase::getCreatedAt);
        if (currentUser.isBankAdmin()) wrapper.eq(CfRiskCase::getBankCode, currentUser.requiredBankCode());

        if (caseId != null && !caseId.isBlank()) {
            String normalizedCaseId = caseId.trim();
            if (normalizedCaseId.matches("\\d{1,7}")) {
                long sequenceId = Long.parseLong(normalizedCaseId);
                if (sequenceId < 1 || sequenceId > 1_000_000) {
                    return CommonResult.error(400, "案例ID必须在 1—1000000 范围内");
                }
                wrapper.eq(CfRiskCase::getId, sequenceId);
            } else {
                // Internal UID compatibility for existing links and integrations.
                wrapper.eq(CfRiskCase::getCaseId, normalizedCaseId);
            }
        }
        if (caseStatus != null && !caseStatus.isBlank()) {
            wrapper.eq(CfRiskCase::getCaseStatus, caseStatus);
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            wrapper.eq(CfRiskCase::getRiskLevel, riskLevel);
        }
        if (recognitionMode != null && !recognitionMode.isBlank()) {
            String mode = recognitionMode.trim().toUpperCase();
            if (!Set.of("NEW", "HISTORICAL").contains(mode)) return CommonResult.error(400, "案例来源必须为历史案例或新增案例");
            wrapper.inSql(CfRiskCase::getCaseId,
                    "SELECT case_id FROM case_processing_pool WHERE recognition_mode='" + mode + "'");
        }
        if (jobId != null && !jobId.isBlank()) {
            // UUID/job ids are generated internally; reject arbitrary SQL fragments before inSql.
            String safeJobId = jobId.trim();
            if (!safeJobId.matches("[A-Za-z0-9_-]{1,100}")) {
                return CommonResult.error(400, "Invalid jobId");
            }
            wrapper.inSql(CfRiskCase::getCaseId,
                    "SELECT case_id FROM case_analysis_job_rel WHERE job_id='" + safeJobId + "'");
        }
        String scopedBankCode = currentUser.scopedBankCode(bankCode);
        if (scopedBankCode != null && !currentUser.isBankAdmin()) {
            wrapper.eq(CfRiskCase::getBankCode, scopedBankCode);
        }

        caseMapper.selectPage(page, wrapper);
        enrichCases(page.getRecords());
        return CommonResult.success(PageResult.of(page.getRecords(), page.getTotal(), safePage, safeSize));
    }

    /**
     * Suspicious transactions are signals, not cases.  Keep them in a separate
     * queue until a scenario (pattern) has produced an event-centred case.
     */
    @GetMapping("/suspicious-transactions")
    public CommonResult<Map<String,Object>> getSuspiciousTransactions(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String scenarioCode,
            @RequestParam(required = false) String status) {
        currentUser.requireAnyRole("badmin", "sadmin");
        StringBuilder where = new StringBuilder(" WHERE s.signal_type='TRANSACTION' ");
        List<Object> args = new java.util.ArrayList<>();
        if (currentUser.isBankAdmin()) { where.append(" AND s.bank_code=? "); args.add(currentUser.requiredBankCode()); }
        if (batchId != null && !batchId.isBlank()) {
            where.append(" AND s.source_batch_id=? ");
            args.add(Long.valueOf(batchId));
        }
        if (scenarioCode != null && !scenarioCode.isBlank()) { where.append(" AND s.scenario_code=? "); args.add(scenarioCode); }
        if (status != null && !status.isBlank()) { where.append(" AND s.status=? "); args.add(status); }
        int safePage = Math.max(pageNum, 1);
        int safeSize = Set.of(10, 20, 50).contains(pageSize) ? pageSize : 10;
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_signal s" + where,
                Long.class, args.toArray());
        String sql = "SELECT s.signal_id,s.scenario_code,s.algorithm_id,s.algorithm_version,s.score,s.decision,array_to_string(s.reason_codes, ',') AS reason_codes,s.status AS signal_status,s.created_at," +
                "t.batch_id,t.source_record_id,t.account_hash,t.counterparty_hash,t.amount,t.currency,t.occurred_at,t.lifecycle_status," +
                "(SELECT r.case_id FROM case_signal_rel r WHERE r.signal_id=s.signal_id LIMIT 1) AS case_id" +
                " FROM risk_signal s JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id " +
                where + " ORDER BY s.created_at DESC LIMIT ? OFFSET ?";
        args.add(safeSize); args.add((safePage - 1) * safeSize);
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<String,Object> result = new HashMap<>();
        result.put("records", rows);
        result.put("total", total == null ? 0L : total);
        result.put("pageNum", safePage);
        result.put("pageSize", safeSize);
        return CommonResult.success(result);
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "2. 查询案例详情", description = "在提交、复核或审批前读取案例完整信息。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    public CommonResult<CfRiskCase> getCaseById(@PathVariable String caseId) {
        CfRiskCase riskCase = caseMapper.selectOne(new LambdaQueryWrapper<CfRiskCase>()
                .eq(CfRiskCase::getCaseId, caseId)
                .eq(CfRiskCase::getDeleted, false));
        if (riskCase != null) currentUser.requireAccessToBank(riskCase.getBankCode());
        if (riskCase != null) enrichCases(List.of(riskCase));
        return riskCase == null ? CommonResult.error(404, "案例不存在") : CommonResult.success(riskCase);
    }

    @PostMapping
    public CommonResult<CfRiskCase> createCase(@RequestBody CfRiskCase riskCase) {
        return CommonResult.error(405,
                "案例只能由非法行为识别流程或已识别存疑交易归并产生，禁止直接新建");
    }

    @PutMapping("/{caseId}")
    public CommonResult<CfRiskCase> updateCase(@PathVariable String caseId, @RequestBody CfRiskCase input) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!Set.of("DRAFT", "REJECTED", "REOPENED").contains(riskCase.getCaseStatus())) {
            return CommonResult.error(409, "仅草稿、已驳回或重开案例可以编辑元数据");
        }
        if (input.getCaseName() == null || input.getCaseName().isBlank()) {
            return CommonResult.error(400, "案例名称不能为空");
        }
        if (input.getScenarioCode() == null || input.getScenarioCode().isBlank()) {
            return CommonResult.error(400, "场景编码不能为空");
        }
        Integer scenarioCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_scenario_template
                WHERE scenario_code=? AND status='ACTIVE'
                  AND (bank_code IS NULL OR bank_code=?)
                """, Integer.class, input.getScenarioCode().trim(), riskCase.getBankCode());
        if (scenarioCount == null || scenarioCount == 0) {
            return CommonResult.error(400, "场景编码不在已启用的场景元数据中");
        }
        riskCase.setCaseName(input.getCaseName().trim());
        riskCase.setDescription(input.getDescription());
        riskCase.setScenarioCode(input.getScenarioCode().trim());
        // Case type is fixed by the recognition task and must not be changed
        // through general case metadata editing.
        riskCase.setRiskLevel(input.getRiskLevel());
        riskCase.setOwnerAnalyst(input.getOwnerAnalyst());
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        enrichCases(List.of(riskCase));
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/overview")
    public CommonResult<CfRiskCase> updateCaseOverview(
            @PathVariable String caseId,
            @RequestBody CaseOverviewUpdateRequest input) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (riskCase.getId() == null || riskCase.getId() < 1 || riskCase.getId() > 1_000_000) {
            return CommonResult.error(409, "案例序号不在 1—1000000 范围内");
        }
        String reportingDirection = nullableText(input.reportingDirection());
        String triggerPoint = nullableText(input.triggerPoint());
        String urgencyLevel = nullableText(input.urgencyLevel());
        String businessStatus = nullableText(input.businessCaseStatus());
        String businessRisk = nullableText(input.businessRiskLevel());
        if (!allowedMetadataOption("reportingDirection", reportingDirection)) {
            return CommonResult.error(400, "报送方向不在启用的案例框架元数据中");
        }
        if (!allowedMetadataOption("triggerPoint", triggerPoint)) {
            return CommonResult.error(400, "案例触发点不在启用的案例框架元数据中");
        }
        if (!allowedMetadataOption("urgencyLevel", urgencyLevel)) {
            return CommonResult.error(400, "紧急程度不在启用的案例框架元数据中");
        }
        if (!allowedMetadataOption("businessCaseStatus", businessStatus)) {
            return CommonResult.error(400, "案例状态不在启用的案例框架元数据中");
        }
        if (!allowedMetadataOption("businessRiskLevel", businessRisk)) {
            return CommonResult.error(400, "风险等级不在启用的案例框架元数据中");
        }
        riskCase.setDescription(nullableText(input.description()));
        riskCase.setBusinessDomain(defaultText(input.businessDomain(), "01-反洗钱"));
        riskCase.setBusinessCaseType(nullableText(input.businessCaseType()));
        riskCase.setReportingDirection(reportingDirection);
        riskCase.setTriggerPoint(triggerPoint);
        riskCase.setUrgencyLevel(urgencyLevel);
        riskCase.setReportedAt(input.reportedAt());
        riskCase.setBusinessCaseStatus(businessStatus);
        riskCase.setBusinessRiskLevel(businessRisk);
        riskCase.setSuspectedCrimeType(nullableText(input.suspectedCrimeType()));
        riskCase.setSuspiciousTransactionFeatureCode(
                nullableText(input.suspiciousTransactionFeatureCode()));
        riskCase.setDisposalMeasure(nullableText(input.disposalMeasure()));
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        enrichCases(List.of(riskCase));
        return CommonResult.success(riskCase);
    }

    @DeleteMapping("/{caseId}")
    @Transactional
    public CommonResult<Void> deleteCase(@PathVariable String caseId) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!Set.of("DRAFT", "REJECTED").contains(riskCase.getCaseStatus())) {
            return CommonResult.error(409, "仅草稿或已驳回案例可以删除");
        }
        int affected = jdbcTemplate.update("""
                UPDATE cf_risk_case
                   SET deleted=true,updated_at=CURRENT_TIMESTAMP
                 WHERE case_id=? AND deleted=false
                """, caseId);
        if (affected != 1) return CommonResult.error(409, "案例状态已发生变化，请刷新列表后重试");
        if (graphWriter != null) {
            try {
                graphWriter.removeCaseSubgraph(caseId);
            } catch (Exception ex) {
                throw new IllegalStateException("案例业务数据已进入删除事务，但 TuGraph 子图清理失败，已回滚本次删除: "
                        + ex.getMessage(), ex);
            }
        }
        return CommonResult.success(null);
    }

    @PutMapping("/{caseId}/submit")
    @Operation(
            summary = "3. 提交案例复核",
            description = "仅 DRAFT 状态可提交。成功后创建 REVIEW、APPROVAL 两步工作流，案例进入 IN_REVIEW。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    @Transactional
    public CommonResult<CfRiskCase> submitCase(@PathVariable String caseId) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase != null && "REOPENED".equals(riskCase.getCaseStatus())) {
            riskCase.setCaseStatus("DRAFT");
        }
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!"DRAFT".equals(riskCase.getCaseStatus())) return CommonResult.error(409, "仅草稿案例可以提交复核");
        riskCase.setCaseStatus("IN_REVIEW");
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        String workflowId = "WF-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workflow_instance
                (workflow_id, case_id, bank_code, workflow_type, current_step, status, initiated_by)
                VALUES (?, ?, ?, 'CASE_REVIEW', 'REVIEW', 'ACTIVE', ?)
                """, workflowId, caseId, riskCase.getBankCode(), currentUser.username());
        jdbcTemplate.update("""
                INSERT INTO workflow_step
                (workflow_id, step_order, step_name, status, started_at)
                VALUES (?, 1, 'REVIEW', 'RUNNING', CURRENT_TIMESTAMP),
                       (?, 2, 'APPROVAL', 'PENDING', NULL)
                """, workflowId, workflowId);
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/review")
    @Operation(
            summary = "4. 复核案例",
            description = "仅 IN_REVIEW 状态可操作。PASSED 后进入 PENDING_APPROVAL；REJECTED 后进入 REJECTED。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    @Transactional
    public CommonResult<CfRiskCase> reviewCase(@PathVariable String caseId, @RequestBody ReviewRequest request) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!"IN_REVIEW".equals(riskCase.getCaseStatus())) return CommonResult.error(409, "案例不在复核状态");
        boolean passed = !"REJECTED".equalsIgnoreCase(request.getReviewResult());
        riskCase.setCaseStatus(passed ? "PENDING_APPROVAL" : "REJECTED");
        riskCase.setReviewer(request.getReviewer());
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        jdbcTemplate.update("""
                INSERT INTO case_review_record
                (review_id, case_id, case_version, bank_code, reviewer, review_type, review_result, review_opinion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "REV-" + UUID.randomUUID(), caseId, riskCase.getCaseVersion(), riskCase.getBankCode(),
                request.getReviewer(), "MANUAL", passed ? "PASSED" : "REJECTED", request.getReviewOpinion());
        jdbcTemplate.update("""
                UPDATE workflow_step SET assignee = ?, status = ?, result = ?, opinion = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE workflow_id = (SELECT workflow_id FROM workflow_instance
                    WHERE case_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1)
                  AND step_name = 'REVIEW'
                """, request.getReviewer(), passed ? "COMPLETED" : "REJECTED",
                passed ? "PASSED" : "REJECTED", request.getReviewOpinion(), caseId);
        if (passed) {
            jdbcTemplate.update("""
                    UPDATE workflow_step SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
                    WHERE workflow_id = (SELECT workflow_id FROM workflow_instance
                        WHERE case_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1)
                      AND step_name = 'APPROVAL'
                    """, caseId);
            jdbcTemplate.update("""
                    UPDATE workflow_instance SET current_step = 'APPROVAL', updated_at = CURRENT_TIMESTAMP
                    WHERE case_id = ? AND status = 'ACTIVE'
                    """, caseId);
        } else {
            jdbcTemplate.update("""
                    UPDATE workflow_instance SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP WHERE case_id = ? AND status = 'ACTIVE'
                    """, caseId);
        }
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/approve")
    @Operation(
            summary = "5. 审批案例",
            description = "仅 PENDING_APPROVAL 状态可操作。APPROVED 后进入 APPROVED；REJECTED 后进入 REJECTED。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    @Transactional
    public CommonResult<CfRiskCase> approveCase(@PathVariable String caseId, @RequestBody ApprovalRequest request) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!"PENDING_APPROVAL".equals(riskCase.getCaseStatus())) return CommonResult.error(409, "案例不在待审批状态");
        boolean approved = !"REJECTED".equalsIgnoreCase(request.getApprovalResult());
        riskCase.setCaseStatus(approved ? "APPROVED" : "REJECTED");
        riskCase.setApprover(request.getApprover());
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        jdbcTemplate.update("""
                INSERT INTO case_approval_record
                (approval_id, case_id, case_version, bank_code, approver, approval_step, approval_result, approval_opinion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "APR-" + UUID.randomUUID(), caseId, riskCase.getCaseVersion(), riskCase.getBankCode(),
                request.getApprover(), "FINAL", approved ? "APPROVED" : "REJECTED", request.getApprovalOpinion());
        jdbcTemplate.update("""
                UPDATE workflow_step SET assignee = ?, status = ?, result = ?, opinion = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE workflow_id = (SELECT workflow_id FROM workflow_instance
                    WHERE case_id = ? ORDER BY created_at DESC LIMIT 1) AND step_name = 'APPROVAL'
                """, request.getApprover(), approved ? "COMPLETED" : "REJECTED",
                approved ? "APPROVED" : "REJECTED", request.getApprovalOpinion(), caseId);
        jdbcTemplate.update("""
                UPDATE workflow_instance SET status = ?, current_step = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE workflow_id = (SELECT workflow_id FROM workflow_instance
                    WHERE case_id = ? ORDER BY created_at DESC LIMIT 1)
                """, approved ? "COMPLETED" : "FAILED", caseId);
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/close")
    public CommonResult<CfRiskCase> closeCase(@PathVariable String caseId) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "案例不存在");
        if (!"APPROVED".equals(riskCase.getCaseStatus())) return CommonResult.error(409, "仅已审批案例可以结案");
        riskCase.setCaseStatus("CLOSED");
        riskCase.setClosedAt(LocalDateTime.now());
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/return")
    @Transactional
    public CommonResult<CfRiskCase> returnCase(@PathVariable String caseId, @RequestBody ReturnRequest request) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "Case does not exist");
        if (!List.of("IN_REVIEW", "PENDING_APPROVAL", "REJECTED").contains(riskCase.getCaseStatus())) {
            return CommonResult.error(409, "Case cannot be returned in its current state");
        }
        riskCase.setCaseStatus("DRAFT");
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        jdbcTemplate.update("""
                UPDATE workflow_instance SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE case_id = ? AND status = 'ACTIVE'
                """, caseId);
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/reassign")
    public CommonResult<CfRiskCase> reassign(@PathVariable String caseId, @RequestBody ReassignRequest request) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "Case does not exist");
        if (request.assignee() == null || request.assignee().isBlank()) {
            return CommonResult.error(400, "Assignee is required");
        }
        riskCase.setOwnerAnalyst(request.assignee());
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        return CommonResult.success(riskCase);
    }

    @PutMapping("/{caseId}/reopen")
    public CommonResult<CfRiskCase> reopen(@PathVariable String caseId) {
        CfRiskCase riskCase = findCase(caseId);
        if (riskCase == null) return CommonResult.error(404, "Case does not exist");
        if (!"CLOSED".equals(riskCase.getCaseStatus())) {
            return CommonResult.error(409, "Only a closed case can be reopened");
        }
        riskCase.setCaseStatus("REOPENED");
        riskCase.setCaseVersion(riskCase.getCaseVersion() + 1);
        riskCase.setClosedAt(null);
        riskCase.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(riskCase);
        return CommonResult.success(riskCase);
    }

    @GetMapping("/{caseId}/workflow")
    @Operation(
            summary = "6. 查询复核审批轨迹",
            description = "返回当前案例最近工作流实例及 REVIEW、APPROVAL 步骤的处理人、状态、结果、意见和时间。",
            tags = OpenApiConfig.CASE_REVIEW_TAG)
    public CommonResult<List<java.util.Map<String, Object>>> workflow(@PathVariable String caseId) {
        if (findCase(caseId) == null) return CommonResult.error(404, "Case does not exist");
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT i.workflow_id AS "workflowId", i.status AS "workflowStatus",
                       i.current_step AS "currentStep", s.step_order AS "stepOrder",
                       s.step_name AS "stepName", s.assignee, s.status, s.result, s.opinion,
                       s.started_at AS "startedAt", s.completed_at AS "completedAt"
                FROM workflow_instance i LEFT JOIN workflow_step s ON s.workflow_id = i.workflow_id
                WHERE i.case_id = ? ORDER BY i.created_at DESC, s.step_order
                """, caseId);
        // Fallback: if no workflow_instance, read from analysis_job_step (auto-generated cases)
        if (rows.isEmpty()) {
            rows = jdbcTemplate.queryForList("""
                SELECT s.job_id AS "workflowId", j.status AS "workflowStatus", NULL AS "currentStep",
                       s.step_order AS "stepOrder", s.step_name AS "stepName",
                       NULL AS assignee, s.status, s.result_json::text AS "result",
                       NULL AS opinion, s.started_at AS "startedAt", s.completed_at AS "completedAt"
                FROM analysis_job_step s
                JOIN analysis_job j ON j.job_id = s.job_id
                WHERE j.job_id IN (
                    SELECT DISTINCT s2.job_id FROM analysis_job_step s2
                    WHERE s2.result_json::text LIKE ?
                )
                ORDER BY j.created_at DESC, s.step_order
                """, "%" + caseId + "%");
        }
        return CommonResult.success(rows);
    }

    @GetMapping("/{caseId}/events")
    public CommonResult<List<CfRiskEvent>> getCaseEvents(@PathVariable String caseId) {
        if (findCase(caseId) == null) return CommonResult.error(404, "Case does not exist");
        List<CfRiskEvent> events = eventMapper.selectList(new LambdaQueryWrapper<CfRiskEvent>()
                .eq(CfRiskEvent::getCaseId, caseId)
                .eq(CfRiskEvent::getDeleted, false));
        return CommonResult.success(events);
    }

    @GetMapping("/{caseId}/signals")
    public CommonResult<List<RiskSignal>> getCaseSignals(@PathVariable String caseId) {
        if (findCase(caseId) == null) return CommonResult.error(404, "Case does not exist");
        List<String> signalIds = jdbcTemplate.queryForList(
                "SELECT signal_id FROM case_signal_rel WHERE case_id = ?", String.class, caseId);
        if (signalIds.isEmpty()) return CommonResult.success(List.of());
        List<RiskSignal> signals = signalMapper.selectList(new LambdaQueryWrapper<RiskSignal>()
                .in(RiskSignal::getSignalId, signalIds)
                .orderByDesc(RiskSignal::getCreatedAt));
        Map<String, String> businessIds = jdbcTemplate.query("""
                SELECT signal_id,business_id FROM case_signal_rel WHERE case_id=?
                """, rs -> {
            Map<String, String> result = new HashMap<>();
            while (rs.next()) result.put(rs.getString("signal_id"), rs.getString("business_id"));
            return result;
        }, caseId);
        signals.forEach(signal -> signal.setBusinessId(businessIds.get(signal.getSignalId())));
        return CommonResult.success(signals);
    }

    /**
     * Returns the raw worker payload persisted in analysis_job_step.  For
     * structured-case workers (DataGraph-TransactionExtraction) whose output
     * is a flat dataset rather than the workspace-json nodes/edges format
     * expected by the frontend model tabs, the payload is transformed into
     * the canonical nodes/edges/events shape so that all detail tabs render.
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/{caseId}/worker-result")
    public CommonResult<Map<String,Object>> workerResult(@PathVariable String caseId) {
        if (findCase(caseId) == null) return CommonResult.error(404, "Case does not exist");
        // Prefer the indexed explicit lineage. The previous JSON LIKE-first
        // query scanned every large worker payload and made detail pages slow.
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.job_id AS "jobId",
                       s.completed_at AS "completedAt", j.job_type AS "jobType",
                       j.job_name AS "jobName"
                FROM case_analysis_job_rel rel
                JOIN analysis_job j ON j.job_id = rel.job_id
                JOIN analysis_job_step s ON s.job_id = j.job_id
                WHERE rel.case_id = ?
                  AND s.step_order = (SELECT MAX(s2.step_order) FROM analysis_job_step s2 WHERE s2.job_id = j.job_id)
                  AND s.status IN ('SUCCEEDED','FAILED')
                ORDER BY s.completed_at DESC NULLS LAST LIMIT 1
                """, caseId);
        // Compatibility fallback for old rows created before lineage existed.
        if (rows.isEmpty()) {
            rows = jdbcTemplate.queryForList("""
                SELECT s.job_id AS "jobId",
                       s.completed_at AS "completedAt", j.job_type AS "jobType",
                       j.job_name AS "jobName"
                FROM analysis_job_step s
                JOIN analysis_job j ON j.job_id = s.job_id
                WHERE s.status='SUCCEEDED' AND s.result_json::text LIKE ?
                ORDER BY s.completed_at DESC NULLS LAST LIMIT 1
                """, "%" + caseId + "%");
        }
        Map<String,Object> out = new HashMap<>();
        out.put("available", !rows.isEmpty());
        out.put("caseId", caseId);
        if (rows.isEmpty()) {
            // Historical/manual cases still have canonical relational facts.
            // Return them as a model projection instead of five empty tabs.
            out.put("source", "RELATIONAL_PROJECTION");
            out.put("workerResult", attachStructuredReport(
                    caseId, transformWorkerResult(caseId, Map.of(), Map.of())));
            return CommonResult.success(out);
        }
        Map<String,Object> row = rows.get(0);
        out.put("jobId", row.get("jobId"));
        out.put("completedAt", row.get("completedAt"));
        out.put("jobType", row.get("jobType"));
        out.put("jobName", row.get("jobName"));
        try {
            String jobType = Objects.toString(row.get("jobType"), "");
            String resultJson = loadCaseWorkerResultJson(Objects.toString(row.get("jobId")), caseId, jobType);
            Map<String,Object> parsed = objectMapper.readValue(resultJson, Map.class);
            Object worker = parsed.get("workerResult");
            Object rawWorker = worker != null ? worker : parsed;
            // Non-structured workers already return the canonical five-model
            // workspace. Never pass it through the structured projection.
            if ("UNSTRUCTURED".equalsIgnoreCase(jobType) && rawWorker instanceof Map<?,?> rawMap
                    && rawMap.get("nodes") instanceof Map) {
                EvidenceTitleNormalizer.normalizeWorkspace((Map<String, Object>) rawMap);
                out.put("workerResult", rawWorker);
                out.put("source", "UNSTRUCTURED_WORKER_RAW");
            } else {
                out.put("workerResult", attachStructuredReport(
                        caseId, transformWorkerResult(caseId, rawWorker, parsed)));
                out.put("source", "STRUCTURED_WORKER_PROJECTION");
            }
            out.put("raw", rawWorker);
        } catch (Exception ex) {
            out.put("workerResult", attachStructuredReport(
                    caseId, transformWorkerResult(caseId, Map.of(), Map.of())));
            out.put("parseError", ex.getMessage());
        }
        return CommonResult.success(out);
    }

    @SuppressWarnings("unchecked")
    Map<String,Object> attachStructuredReport(String caseId, Map<String,Object> workerResult) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT recognition_mode AS "recognitionMode",
                       COALESCE((case_document->'analysis_texts')::text,'{}') AS "analysisTexts"
                  FROM structured_case_library
                 WHERE case_id=? AND status='ACTIVE'
                 LIMIT 1
                """, caseId);
        if (rows.isEmpty()) return workerResult;
        Map<String,Object> stored = rows.get(0);
        Map<String,Object> analysisTexts;
        try {
            analysisTexts = objectMapper.readValue(
                    Objects.toString(stored.get("analysisTexts"), "{}"), Map.class);
        } catch (Exception ignored) {
            return workerResult;
        }
        if (analysisTexts.isEmpty()) return workerResult;

        String recognitionMode = Objects.toString(
                stored.get("recognitionMode"), "").trim().toUpperCase();
        String analysisText = analysisTexts.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (analysisText.isEmpty()) return workerResult;

        Map<String,Object> root = new LinkedHashMap<>(workerResult);
        root.put("recognitionMode", recognitionMode);
        List<Map<String,Object>> results = new ArrayList<>();
        Object rawResults = root.get("results");
        if (rawResults instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?,?> item) {
                    results.add(new LinkedHashMap<>((Map<String,Object>) item));
                }
            }
        }
        Map<String,Object> caseResult = results.stream()
                .filter(item -> caseId.equals(Objects.toString(item.get("caseId"), "")))
                .findFirst()
                .orElseGet(() -> {
                    Map<String,Object> created = new LinkedHashMap<>();
                    created.put("caseId", caseId);
                    results.add(created);
                    return created;
                });
        caseResult.put("recognitionMode", recognitionMode);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("analysisTexts", analysisTexts);
        report.put("analysisText", analysisText);
        report.put("source", "HISTORICAL".equals(recognitionMode)
                ? "EXISTING_ANALYSIS_TEXT" : "GENERATED_ANALYSIS_TEXT");
        report.put("generated", "NEW".equals(recognitionMode));
        caseResult.put("suspiciousReport", report);
        caseResult.put("analysisReport", new LinkedHashMap<>(report));
        root.put("results", results);
        return root;
    }

    String loadCaseWorkerResultJson(String jobId, String caseId, String jobType) {
        if (!"STRUCTURED".equalsIgnoreCase(jobType)) {
            return jdbcTemplate.queryForObject("""
                SELECT result_json::text FROM analysis_job_step
                 WHERE job_id=? ORDER BY step_order DESC LIMIT 1
                """, String.class, jobId);
        }
        // The Xi'an structured pipeline returns one result per case. Keep only
        // the requested result so a detail-page read does not deserialize the
        // complete (up to 500-case) batch. Some deployed versions persisted the
        // pipeline response directly, while others nested it under workerResult;
        // normalize both layouts to the wrapper consumed below.
        List<String> pipelineSlices = jdbcTemplate.queryForList("""
            WITH step AS (
              SELECT result_json FROM analysis_job_step
               WHERE job_id=? ORDER BY step_order DESC LIMIT 1
            ), payload AS (
              SELECT CASE
                WHEN jsonb_typeof(result_json->'workerResult')='object'
                  THEN result_json->'workerResult'
                ELSE result_json
              END AS value
              FROM step
            ), matched AS (
              SELECT payload.value, item.value AS case_result
              FROM payload
              JOIN LATERAL jsonb_array_elements(
                COALESCE(payload.value->'results','[]'::jsonb)
              ) item(value) ON item.value->>'caseId'=?
            )
            SELECT jsonb_build_object(
              'caseId', ?, 'caseIds', jsonb_build_array(?),
              'workerResult',
                (matched.value - 'results')
                || jsonb_build_object('results',jsonb_build_array(matched.case_result))
            )::text
            FROM matched
            """, String.class, jobId, caseId, caseId, caseId);
        if (!pipelineSlices.isEmpty()) return pipelineSlices.get(0);

        // Compatibility with the retired flat structured dataset response.
        List<String> slices = jdbcTemplate.queryForList("""
            WITH step AS (
              SELECT result_json FROM analysis_job_step
               WHERE job_id=? ORDER BY step_order DESC LIMIT 1
            )
            SELECT jsonb_build_object(
              'caseId', ?, 'caseIds', jsonb_build_array(?),
              'workerResult', jsonb_build_object(
                'worker', step.result_json->'workerResult'->'worker',
                'adapterVersion', step.result_json->'workerResult'->'adapterVersion',
                'jobId', step.result_json->'workerResult'->'jobId',
                'status', step.result_json->'workerResult'->'status',
                'caseStrategy', step.result_json->'workerResult'->'caseStrategy',
                'evaluation', step.result_json->'workerResult'->'evaluation',
                'dataset', jsonb_build_object(
                  'accounts', step.result_json->'workerResult'->'dataset'->'accounts',
                  'patterns', step.result_json->'workerResult'->'dataset'->'patterns',
                  'transactions',
                    ((step.result_json->'workerResult'->'dataset'->'transactions') - 'componentCases')
                    || jsonb_build_object('componentCases',jsonb_build_array(component.value))
                )
              )
            )::text
            FROM step
            JOIN LATERAL jsonb_array_elements_text(step.result_json->'caseIds') WITH ORDINALITY ids(value,ord)
              ON ids.value=?
            JOIN LATERAL jsonb_array_elements(step.result_json->'workerResult'->'dataset'->'transactions'->'componentCases')
              WITH ORDINALITY component(value,ord) ON component.ord=ids.ord
            """, String.class, jobId, caseId, caseId, caseId);
        if (!slices.isEmpty()) return slices.get(0);
        return jdbcTemplate.queryForObject("""
            SELECT result_json::text FROM analysis_job_step
             WHERE job_id=? ORDER BY step_order DESC LIMIT 1
            """, String.class, jobId);
    }

    /**
     * Transform DataGraph-TransactionExtraction structured output (flat dataset)
     * into the canonical workspace-json shape (nodes/edges) expected by
     * CaseDetail.vue model tabs.
     */
    @SuppressWarnings("unchecked")
    private Map<String,Object> transformWorkerResult(String caseId, Object raw, Map<String,Object> wrapper) {
        Map<String,Object> root = new LinkedHashMap<>();
        List<Map<String,Object>> cases = new ArrayList<>();
        List<Map<String,Object>> events = new ArrayList<>();
        List<Map<String,Object>> evidences = new ArrayList<>();
        List<Map<String,Object>> accounts = new ArrayList<>();
        List<Map<String,Object>> customers = new ArrayList<>();
        Map<String,List<Map<String,Object>>> edges = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            root.put("nodes", Map.of("cases",cases,"events",events,"evidences",evidences,"accounts",accounts,"customers",customers));
            root.put("edges", edges);
            return root;
        }
        Map<String,Object> r = (Map<String,Object>) raw;
        root.putAll(r);
        List<Map<String,Object>> eventFacts = structuredEventFacts(caseId, r, wrapper);
        List<Map<String,Object>> caseTransactions = jdbcTemplate.queryForList("""
            SELECT s.signal_id,s.signal_type,s.algorithm_id,s.algorithm_version,s.score,s.decision,
                   s.source_ref_id,s.input_data_hash,t.account_hash,t.counterparty_hash,t.amount,
                   t.currency,t.occurred_at,t.transaction_type,t.channel,t.source_record_id
              FROM case_signal_rel rel JOIN risk_signal s ON s.signal_id=rel.signal_id
              LEFT JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
             WHERE rel.case_id=? ORDER BY s.created_at,t.id
            """, caseId);

        // Pull DB records for live event / case data
        List<Map<String,Object>> dbEvents = jdbcTemplate.queryForList(
            """
                SELECT event_id, event_name, event_type, event_time, confidence,
                      evidence_refs::text AS evidence_refs, risk_score, risk_level, rule_name, subject_count
                  FROM cf_risk_event WHERE case_id=? AND deleted=false ORDER BY event_time NULLS LAST,id LIMIT 100
                """, caseId);
        Map<String,Object> dbCase = new HashMap<>();
        try {
            dbCase = jdbcTemplate.queryForMap(
                "SELECT case_id,case_name,description,scenario_code,case_source,case_type,case_status,risk_score,risk_level,transaction_count FROM cf_risk_case WHERE case_id=?", caseId);
        } catch (Exception ignored) {}

        // Build case node
        Map<String,Object> caseNode = new LinkedHashMap<>();
        caseNode.put("uid", caseId);
        caseNode.put("name", value(dbCase.get("case_name"), ""));
        caseNode.put("description", value(dbCase.get("description"), ""));
        caseNode.put("business_domain", value(dbCase.get("scenario_code"), ""));
        caseNode.put("type", value(dbCase.get("case_type"), "TRANSACTION_CLUSTER"));
        caseNode.put("case_source", value(dbCase.get("case_source"), "STRUCT_SUSPECTED"));
        caseNode.put("status", value(dbCase.get("case_status"), "DRAFT"));
        caseNode.put("risk_score", dbCase.getOrDefault("risk_score", ""));
        caseNode.put("risk_level", value(dbCase.get("risk_level"), ""));
        caseNode.put("transaction_count", caseTransactions.stream()
                .filter(item -> item.get("amount") != null).count());
        cases.add(caseNode);

        // Build event nodes from DB cf_risk_event
        for (int eventIndex = 0; eventIndex < dbEvents.size(); eventIndex++) {
            Map<String,Object> ev = dbEvents.get(eventIndex);
            Map<String,Object> fact = eventIndex < eventFacts.size() ? eventFacts.get(eventIndex) : Map.of();
            Object storedEvidence = parseJsonValue(ev.get("evidence_refs"));
            Map<String,Object> evidenceFact = storedEvidence instanceof Map<?,?> map && !map.isEmpty()
                    ? (Map<String,Object>) map : fact;
            Map<String,Object> linkedTransaction = linkedTransaction(evidenceFact, caseTransactions);
            if (!linkedTransaction.isEmpty()) evidenceFact = transactionFact(linkedTransaction, evidenceFact);
            Map<String,Object> en = new LinkedHashMap<>();
            en.put("uid", value(ev.get("event_id"), ""));
            en.put("name", value(ev.get("event_name"), ""));
            en.put("type", value(ev.get("event_type"), ""));
            en.put("started_at", evidenceFact.getOrDefault("timestamp", ev.get("event_time")));
            en.put("confidence", ev.getOrDefault("confidence", ""));
            en.put("evidence_refs", evidenceFact);
            en.put("source_account", evidenceFact.getOrDefault("source", ""));
            en.put("target_account", evidenceFact.getOrDefault("target", ""));
            en.put("amount", evidenceFact.getOrDefault("amount", ""));
            en.put("currency", evidenceFact.getOrDefault("currency", ""));
            en.put("payment_format", evidenceFact.getOrDefault("paymentFormat", ""));
            en.put("event_text", dbEvents.size() == 1 && eventFacts.size() > 1
                    ? eventFacts.stream().limit(2)
                        .map(factItem -> structuredEventText(en, factItem))
                        .collect(java.util.stream.Collectors.joining(" "))
                    : structuredEventText(en, evidenceFact));
            en.put("data_source", linkedTransaction.isEmpty() ? "CF_RISK_EVENT" : "RISK_SIGNAL_TRANSACTION");
            en.put("signal_ids", evidenceFact.getOrDefault("signalIds", List.of()));
            en.put("risk_score", ev.getOrDefault("risk_score", ""));
            en.put("risk_level", value(ev.get("risk_level"), ""));
            en.put("rule_name", value(ev.get("rule_name"), ""));
            en.put("subject_count", ev.getOrDefault("subject_count", 0));
            events.add(en);
        }

        // Build evidence summary from dataset stats
        Map<String,Object> dataset = r.get("dataset") instanceof Map ? (Map<String,Object>) r.get("dataset") : new HashMap<>();
        Map<String,Object> tx = dataset.get("transactions") instanceof Map ? (Map<String,Object>) dataset.get("transactions") : new HashMap<>();
        Map<String,Object> acct = dataset.get("accounts") instanceof Map ? (Map<String,Object>) dataset.get("accounts") : new HashMap<>();
        Map<String,Object> pat = dataset.get("patterns") instanceof Map ? (Map<String,Object>) dataset.get("patterns") : new HashMap<>();
        Map<String,Object> evl = r.get("evaluation") instanceof Map ? (Map<String,Object>) r.get("evaluation") : new HashMap<>();

        boolean hasWorkerDataset = tx.containsKey("transactionRows") || tx.containsKey("suspiciousRows");
        if (hasWorkerDataset) {
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("uid", "EVID-" + caseId);
            evidence.put("type", "ALGORITHM_ANALYSIS_REPORT");
            evidence.put("name", "结构化 Worker 数据集评估报告（批次级）");
            evidence.put("summary", String.format("批次级统计：交易数据%s笔、候选%s笔、连通分量%s个、账户%s个；当前案例：关联%s笔交易、%s个事件",
                    tx.getOrDefault("transactionRows",0), tx.getOrDefault("suspiciousRows",0),
                    tx.getOrDefault("candidateComponents",0), acct.getOrDefault("rows",0),
                    caseTransactions.size(), dbEvents.size()));
            evidence.put("source", "DataGraph-TransactionExtraction");
            evidence.put("source_file", tx.getOrDefault("file", ""));
            evidence.put("sha256", tx.getOrDefault("sha256", ""));
            evidences.add(evidence);
        } else {
            for (Map<String,Object> transaction : caseTransactions) {
                Map<String,Object> evidence = new LinkedHashMap<>();
                String signalId = value(transaction.get("signal_id"), "");
                evidence.put("uid", "EVID-SIGNAL-" + signalId);
                evidence.put("type", "SUSPICIOUS_TRANSACTION_SIGNAL");
                evidence.put("name", "存疑交易证据 " + value(transaction.get("source_record_id"), signalId));
                evidence.put("summary", physicalSignalSummary(transaction));
                evidence.put("source", value(transaction.get("algorithm_id"), "RISK_SIGNAL") + "@"
                        + value(transaction.get("algorithm_version"), "unknown"));
                evidence.put("source_ref_id", transaction.get("source_ref_id"));
                evidence.put("sha256", transaction.get("input_data_hash"));
                evidence.put("signal_id", signalId);
                evidences.add(evidence);
            }
        }

        // Use the same event-sized transaction window as TuGraph.
        List<Map<String,Object>> accountRows = jdbcTemplate.queryForList("""
            WITH selected AS (
              SELECT t.account_hash,t.counterparty_hash
              FROM case_signal_rel r JOIN risk_signal s ON s.signal_id=r.signal_id
              JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
              WHERE r.case_id=?
              ORDER BY t.id
              LIMIT ?
            )
            SELECT DISTINCT account_hash FROM (
              SELECT account_hash FROM selected
              UNION
              SELECT counterparty_hash AS account_hash FROM selected
            ) a WHERE account_hash IS NOT NULL LIMIT 200""", caseId,
                Math.max(((Number) dbCase.getOrDefault("transaction_count", dbEvents.size())).intValue(), 1));
        if (!eventFacts.isEmpty()) {
            LinkedHashSet<String> accountNumbers = new LinkedHashSet<>();
            eventFacts.forEach(fact -> {
                accountNumbers.add(value(fact.get("source"), ""));
                accountNumbers.add(value(fact.get("target"), ""));
            });
            accountNumbers.remove("");
            for (String accountNo : accountNumbers) {
                Map<String,Object> an = new LinkedHashMap<>();
                an.put("uid", "ACC-" + caseId + "-" + Integer.toHexString(accountNo.hashCode()));
                an.put("account_no", accountNo);
                an.put("type", "TRANSACTION_ACCOUNT");
                an.put("source", "WORKER_COMPONENT_TRANSACTION");
                accounts.add(an);
            }
        } else for (Map<String,Object> row : accountRows) {
            String hash = value(row.get("account_hash"), "");
            Map<String,Object> an = new LinkedHashMap<>();
            an.put("uid", "ACC-" + caseId + "-" + hash);
            an.put("account_no", "");
            an.put("account_hash", hash);
            an.put("type", "TRANSACTION_ACCOUNT");
            an.put("source", "RISK_TRANSACTION_MATERIALIZED");
            accounts.add(an);
        }

        // New structured worker versions resolve IBM account rows to actual
        // entities. Do not manufacture customers from account hashes.
        Object accountEntities = structuredAccountEntities(caseId, r, wrapper);
        if (accountEntities instanceof List<?> entities) for (Object entity : entities) {
            if (!(entity instanceof Map<?,?> item)) continue;
            String resolvedAccount = value(item.get("accountNo"), "");
            for (Map<String,Object> account : accounts) {
                if (normalizeAccountNo(value(account.get("account_no"), "")).equals(normalizeAccountNo(resolvedAccount))) {
                    account.put("holder_name", value(item.get("entityName"), ""));
                    account.put("customer_no", value(item.get("entityId"), ""));
                    account.put("bank_name", value(item.get("bankName"), ""));
                    account.put("source", "IBM_ACCOUNT_FILE");
                }
            }
            Map<String,Object> customer = new LinkedHashMap<>();
            customer.put("uid", value(item.get("entityId"), value(item.get("entityName"), "")));
            customer.put("name", value(item.get("entityName"), ""));
            customer.put("customer_no", value(item.get("entityId"), ""));
            customer.put("source", "IBM_ACCOUNT_FILE");
            if (!value(customer.get("uid"), "").isBlank()) customers.add(customer);
        }

        // Build edges: case-contains-evidence, evidence-contains-event
        if (!evidences.isEmpty()) {
            List<Map<String,Object>> ceEdges = new ArrayList<>();
            for (Map<String,Object> evidence : evidences) {
                Map<String,Object> ce = new LinkedHashMap<>();
                ce.put("source", cases.get(0).get("uid"));
                ce.put("target", evidence.get("uid"));
                ce.put("description", "案例包含物理证据");
                ceEdges.add(ce);
            }
            edges.put("CONTAINS_EVIDENCE", ceEdges);

            List<Map<String,Object>> eeEdges = new ArrayList<>();
            for (Map<String,Object> evn : events) {
                for (Map<String,Object> evidence : evidences) {
                    Object eventSignalIds = evn.get("signal_ids");
                    String evidenceSignalId = value(evidence.get("signal_id"), "");
                    boolean workerReport = "ALGORITHM_ANALYSIS_REPORT".equals(evidence.get("type"));
                    boolean exactSignalMatch = eventSignalIds instanceof List<?> ids
                            && ids.stream().anyMatch(id -> evidenceSignalId.equals(String.valueOf(id)));
                    boolean onlyPhysicalEvidence = evidences.size() == 1
                            && (!(eventSignalIds instanceof List<?> ids) || ids.isEmpty());
                    if (!workerReport && !exactSignalMatch && !onlyPhysicalEvidence) continue;
                    Map<String,Object> ee = new LinkedHashMap<>();
                    ee.put("source", evidence.get("uid"));
                    ee.put("target", evn.get("uid"));
                    ee.put("description", "案例信号证据支持事件");
                    eeEdges.add(ee);
                }
            }
            if (!eeEdges.isEmpty()) edges.put("SUPPORTS_EVENT", eeEdges);
        }

        root.put("nodes", Map.of("cases",cases,"events",events,"evidences",evidences,"accounts",accounts,"customers",customers));
        root.put("edges", edges);
        root.put("meta", Map.of("schema_version", value(r.get("adapterVersion"), "unknown"),
                "transformer", "STRUCTURED_RELATIONAL_PROJECTION"));
        root.put("final", Map.of("summary", hasWorkerDataset
                ? String.format("Worker批次统计: 候选%s笔交易、%s个连通分量；当前案例落库: %s笔关联交易、%s个事件 (%s)",
                    tx.getOrDefault("suspiciousRows",0), tx.getOrDefault("candidateComponents",0),
                    caseTransactions.size(), dbEvents.size(), value(evl.get("purpose"), "算法评估"))
                : String.format("关系库案例事实: %s个风险信号、%s笔关联交易、%s个事件",
                    caseTransactions.size(), caseTransactions.stream().filter(item -> item.get("amount") != null).count(), dbEvents.size())));
        return root;
    }

    private Map<String,Object> linkedTransaction(Map<String,Object> evidence, List<Map<String,Object>> transactions) {
        Object ids = evidence.get("signalIds");
        if (ids instanceof List<?> signalIds) for (Object id : signalIds) {
            for (Map<String,Object> transaction : transactions)
                if (String.valueOf(id).equals(String.valueOf(transaction.get("signal_id")))) return transaction;
        }
        return transactions.size() == 1 ? transactions.get(0) : Map.of();
    }

    private Map<String,Object> transactionFact(Map<String,Object> transaction, Map<String,Object> existing) {
        Map<String,Object> fact = new LinkedHashMap<>(existing);
        if (existing.containsKey("source")) fact.put("evidenceSource", existing.get("source"));
        fact.put("source", transaction.get("account_hash"));
        fact.put("target", transaction.get("counterparty_hash"));
        fact.put("amount", transaction.get("amount"));
        fact.put("currency", transaction.get("currency"));
        fact.put("timestamp", transaction.get("occurred_at"));
        fact.put("paymentFormat", value(transaction.get("channel"), value(transaction.get("transaction_type"), "")));
        fact.put("sourceRecordId", transaction.get("source_record_id"));
        return fact;
    }

    private String physicalSignalSummary(Map<String,Object> transaction) {
        return String.format("信号%s由%s识别，关联交易%s，金额%s %s，发生时间%s，判定%s，评分%s",
                value(transaction.get("signal_id"), ""), value(transaction.get("algorithm_id"), ""),
                value(transaction.get("source_record_id"), ""), value(transaction.get("amount"), "未记录"),
                value(transaction.get("currency"), ""), value(transaction.get("occurred_at"), "未记录"),
                value(transaction.get("decision"), ""), value(transaction.get("score"), ""));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> structuredEventFacts(String caseId, Map<String,Object> raw,
                                                           Map<String,Object> wrapper) {
        Object idsValue = wrapper.get("caseIds");
        if (!(idsValue instanceof List<?> caseIds)) return List.of();
        int caseIndex = -1;
        for (int i=0;i<caseIds.size();i++) if (caseId.equals(String.valueOf(caseIds.get(i)))) { caseIndex=i; break; }
        if (caseIndex < 0) return List.of();
        Object datasetValue = raw.get("dataset");
        if (!(datasetValue instanceof Map<?,?> dataset)) return List.of();
        Object txValue = dataset.get("transactions");
        if (!(txValue instanceof Map<?,?> tx)) return List.of();
        Object componentsValue = tx.get("componentCases");
        if (!(componentsValue instanceof List<?> components) || caseIndex >= components.size()
                || !(components.get(caseIndex) instanceof Map<?,?> component)) return List.of();
        Object transactionsValue = component.get("transactions");
        if (!(transactionsValue instanceof List<?> transactions)) return List.of();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object item : transactions) {
            if (!(item instanceof Map<?,?> map)) continue;
            String source = value(map.get("source"), "");
            if (source.isBlank()) continue;
            Map<String,Object> fact = new LinkedHashMap<>();
            map.forEach((key,val) -> fact.put(String.valueOf(key), val));
            result.add(fact);
            if (result.size() >= 50) break;
        }
        return result;
    }

    private Object structuredAccountEntities(String caseId, Map<String,Object> raw,
                                             Map<String,Object> wrapper) {
        Object idsValue = wrapper.get("caseIds");
        if (!(idsValue instanceof List<?> caseIds)) return List.of();
        int caseIndex = -1;
        for (int i=0;i<caseIds.size();i++) if (caseId.equals(String.valueOf(caseIds.get(i)))) { caseIndex=i; break; }
        if (caseIndex < 0 || !(raw.get("dataset") instanceof Map<?,?> dataset)
                || !(dataset.get("transactions") instanceof Map<?,?> tx)
                || !(tx.get("componentCases") instanceof List<?> components)
                || caseIndex >= components.size() || !(components.get(caseIndex) instanceof Map<?,?> component)) return List.of();
        Object entities = component.get("accountEntities");
        return entities == null ? List.of() : entities;
    }

    private String normalizeAccountNo(String value) {
        int separator = value.indexOf(':');
        if (separator < 0) return value;
        String bank = value.substring(0, separator).replaceFirst("^0+(?!$)", "");
        return bank + value.substring(separator);
    }

    private String structuredEventText(Map<String,Object> event, Map<String,Object> fact) {
        String source = value(fact.get("source"), "相关账户");
        String target = value(fact.get("target"), "对手账户");
        String timestamp = value(fact.get("timestamp"), "时间待核验");
        String amount = value(fact.get("amount"), "金额待核验");
        String currency = value(fact.get("currency"), "");
        String payment = value(fact.get("paymentFormat"), "");
        return source + "于" + timestamp + (payment.isBlank() ? "" : "通过" + payment + "方式")
                + "向" + target + "转账" + amount + (currency.isBlank() ? "" : " " + currency) + "。";
    }

    private static String value(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private Object parseJsonValue(Object value) {
        if (value == null) return Map.of();
        try { return objectMapper.readValue(String.valueOf(value), Object.class); }
        catch (Exception ignored) { return value; }
    }

    private void enrichCases(List<CfRiskCase> cases) {
        if (cases == null || cases.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(cases.size(), "?"));
        List<Object> ids = cases.stream().map(CfRiskCase::getCaseId).map(value -> (Object) value).toList();
        String sql = """
            SELECT c.case_id AS "caseId",
              (SELECT COUNT(*) FROM cf_risk_event e WHERE e.case_id=c.case_id AND e.deleted=false) AS "eventCount",
              (SELECT COUNT(*) FROM case_signal_rel r WHERE r.case_id=c.case_id) AS "signalCount",
              (SELECT COUNT(DISTINCT s.source_transaction_id)
                 FROM case_signal_rel r JOIN risk_signal s ON s.signal_id=r.signal_id
                WHERE r.case_id=c.case_id AND s.source_transaction_id IS NOT NULL) AS "transactionCount",
              (SELECT COALESCE(SUM(tx.amount),0) FROM (
                   SELECT DISTINCT t.id,t.amount
                   FROM case_signal_rel r JOIN risk_signal s ON s.signal_id=r.signal_id
                   JOIN risk_transaction_materialized t ON t.id=s.source_transaction_id
                   WHERE r.case_id=c.case_id) tx) AS "totalAmount",
              (SELECT string_agg(code,'、') FROM (
                   SELECT DISTINCT e.event_standard_code AS code
                   FROM cf_risk_event e WHERE e.case_id=c.case_id AND e.deleted=false
                     AND e.event_standard_code IS NOT NULL ORDER BY code LIMIT 3) p) AS "patternName"
              ,(SELECT p.recognition_mode FROM case_processing_pool p WHERE p.case_id=c.case_id) AS "recognitionMode"
              ,(SELECT COUNT(*) FROM behavior_pattern_occurrence p
                  WHERE p.case_id=c.case_id AND p.status='ACTIVE') AS "patternProductCount"
              ,(SELECT COUNT(*) FROM case_matter_explanation m
                  WHERE m.case_id=c.case_id AND m.status='ACTIVE') AS "matterProductCount"
              ,(SELECT COUNT(*) FROM risk_event_hypothesis r
                  WHERE r.case_id=c.case_id AND r.status='ACTIVE') AS "riskProductCount"
              ,(SELECT COUNT(*) FROM technique_occurrence t
                  WHERE t.case_id=c.case_id AND t.status<>'SUPERSEDED') AS "techniqueProductCount"
              ,(SELECT COUNT(*) FROM case_explanation_snapshot x
                  WHERE x.case_id=c.case_id) AS "productSnapshotCount"
              ,(SELECT MAX(x.created_at) FROM case_explanation_snapshot x
                  WHERE x.case_id=c.case_id) AS "lastProducedAt"
            FROM cf_risk_case c WHERE c.case_id IN (%s)
            """.formatted(placeholders);
        List<Map<String,Object>> stats = jdbcTemplate.queryForList(sql, ids.toArray());
        List<Map<String,Object>> matterRows = jdbcTemplate.queryForList("""
            SELECT case_id AS "caseId",matter_id AS "matterId",matter_type AS "matterType"
            FROM case_matter_explanation
            WHERE status='ACTIVE' AND case_id IN (%s)
            """.formatted(placeholders), ids.toArray());
        Map<String,List<Map<String,Object>>> mattersByCase = new HashMap<>();
        for (Map<String,Object> row : matterRows) {
            mattersByCase.computeIfAbsent(String.valueOf(row.get("caseId")), ignored -> new ArrayList<>())
                    .add(row);
        }
        Map<String,Map<String,Object>> byId = new HashMap<>();
        for (Map<String,Object> row : stats) byId.put(String.valueOf(row.get("caseId")), row);
        for (CfRiskCase item : cases) {
            Map<String,Object> row = byId.get(item.getCaseId());
            if (row == null) continue;
            item.setEventCount(((Number) row.get("eventCount")).intValue());
            item.setSignalCount(((Number) row.get("signalCount")).intValue());
            item.setTransactionCount(((Number) row.get("transactionCount")).intValue());
            item.setTotalAmount((java.math.BigDecimal) row.get("totalAmount"));
            item.setPatternName(Objects.toString(row.get("patternName"), ""));
            item.setRecognitionMode(Objects.toString(row.get("recognitionMode"), ""));
            item.setPatternProductCount(((Number) row.get("patternProductCount")).intValue());
            int atomicMatterCount = ((Number) row.get("matterProductCount")).intValue();
            item.setAtomicMatterProductCount(atomicMatterCount);
            item.setMatterProductCount(convergedMatterCount(
                    mattersByCase.getOrDefault(item.getCaseId(), List.of())));
            item.setRiskProductCount(((Number) row.get("riskProductCount")).intValue());
            item.setTechniqueProductCount(((Number) row.get("techniqueProductCount")).intValue());
            item.setProductSnapshotCount(((Number) row.get("productSnapshotCount")).intValue());
            Object producedAt = row.get("lastProducedAt");
            if (producedAt instanceof java.sql.Timestamp timestamp) {
                item.setLastProducedAt(timestamp.toLocalDateTime());
            } else if (producedAt instanceof java.time.OffsetDateTime offsetDateTime) {
                item.setLastProducedAt(offsetDateTime.toLocalDateTime());
            } else if (producedAt instanceof java.time.LocalDateTime localDateTime) {
                item.setLastProducedAt(localDateTime);
            }
            int productCount = item.getPatternProductCount() + item.getMatterProductCount()
                    + item.getRiskProductCount() + item.getTechniqueProductCount();
            item.setProductionStatus(productCount > 0 ? "PRODUCED"
                    : item.getEventCount() > 0 ? "PENDING" : "NOT_READY");
        }
    }

    private int convergedMatterCount(List<Map<String,Object>> matters) {
        if (matters == null || matters.isEmpty()) return 0;
        Set<String> groupedMatterIds = new HashSet<>();
        int groupCount = 0;
        for (Set<String> factGroup : MATTER_FACT_GROUPS) {
            List<Map<String,Object>> members = matters.stream()
                    .filter(row -> factGroup.contains(Objects.toString(row.get("matterType"), "")))
                    .toList();
            if (members.size() < 2) continue;
            groupCount++;
            members.forEach(row -> groupedMatterIds.add(Objects.toString(row.get("matterId"), "")));
        }
        return groupCount + matters.size() - groupedMatterIds.size();
    }

    private CfRiskCase findCase(String caseId) {
        CfRiskCase riskCase = caseMapper.selectOne(new LambdaQueryWrapper<CfRiskCase>()
                .eq(CfRiskCase::getCaseId, caseId)
                .eq(CfRiskCase::getDeleted, false));
        if (riskCase != null) currentUser.requireAccessToBank(riskCase.getBankCode());
        return riskCase;
    }

    private boolean allowedMetadataOption(String fieldCode, String value) {
        if (value == null || value.isBlank()) return true;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM case_framework_option_metadata
                WHERE field_code=? AND option_value=? AND status='ACTIVE'
                """, Integer.class, fieldCode, value);
        return count != null && count > 0;
    }

    private static String nullableText(String value) {
        if (value == null || value.isBlank() || "待补充".equals(value.trim())) return null;
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        String normalized = nullableText(value);
        return normalized == null ? fallback : normalized;
    }

    @Schema(name = "CaseReviewRequest", description = "案例复核请求")
    public static class ReviewRequest {
        @Schema(description = "复核人", example = "reviewer01", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reviewer;
        @Schema(description = "复核意见", example = "交易链路和证据完整，同意进入审批")
        private String reviewOpinion;
        @Schema(description = "复核结果", example = "PASSED", allowableValues = {"PASSED", "REJECTED"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String reviewResult;

        public String getReviewer() { return reviewer; }
        public void setReviewer(String reviewer) { this.reviewer = reviewer; }
        public String getReviewOpinion() { return reviewOpinion; }
        public void setReviewOpinion(String reviewOpinion) { this.reviewOpinion = reviewOpinion; }
        public String getReviewResult() { return reviewResult; }
        public void setReviewResult(String reviewResult) { this.reviewResult = reviewResult; }
    }

    @Schema(name = "CaseApprovalRequest", description = "案例审批请求")
    public static class ApprovalRequest {
        @Schema(description = "审批人", example = "approver01", requiredMode = Schema.RequiredMode.REQUIRED)
        private String approver;
        @Schema(description = "审批意见", example = "审批通过")
        private String approvalOpinion;
        @Schema(description = "审批结果", example = "APPROVED", allowableValues = {"APPROVED", "REJECTED"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String approvalResult;

        public String getApprover() { return approver; }
        public void setApprover(String approver) { this.approver = approver; }
        public String getApprovalOpinion() { return approvalOpinion; }
        public void setApprovalOpinion(String approvalOpinion) { this.approvalOpinion = approvalOpinion; }
        public String getApprovalResult() { return approvalResult; }
        public void setApprovalResult(String approvalResult) { this.approvalResult = approvalResult; }
    }

    public record ReturnRequest(String reason) {}
    public record ReassignRequest(String assignee) {}
    public record CaseOverviewUpdateRequest(
            String description,
            String businessDomain,
            String businessCaseType,
            String reportingDirection,
            String triggerPoint,
            String urgencyLevel,
            LocalDateTime reportedAt,
            String businessCaseStatus,
            String businessRiskLevel,
            String suspectedCrimeType,
            String suspiciousTransactionFeatureCode,
            String disposalMeasure) {}
}
