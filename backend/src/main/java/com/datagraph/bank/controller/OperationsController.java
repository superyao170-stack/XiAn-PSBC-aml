package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only task list endpoint shared by the AML and anti-fraud workbenches. */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;

    public OperationsController(JdbcTemplate jdbcTemplate, CurrentUser currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    @GetMapping("/analysis/jobs")
    public CommonResult<Map<String, Object>> jobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String scenarioCode,
            @RequestParam(required = false) Long batchId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        String bankCode = currentUser.isBankAdmin() ? currentUser.requiredBankCode() : null;
        int page = Math.max(pageNum, 1);
        int size = Set.of(10, 20, 50).contains(pageSize) ? pageSize : 10;
        StringBuilder where = new StringBuilder(" WHERE j.deleted=false");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" AND j.status=?"); args.add(status); }
        if (bankCode != null && !bankCode.isBlank()) { where.append(" AND j.bank_code=?"); args.add(bankCode); }
        if (jobType != null && !jobType.isBlank()) { where.append(" AND j.job_type=?"); args.add(jobType); }
        if (scenarioCode != null && !scenarioCode.isBlank()) { where.append(" AND j.scenario_code=?"); args.add(scenarioCode); }
        if (batchId != null) { where.append(" AND j.batch_id=?"); args.add(batchId); }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job j" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                SELECT j.job_id AS "jobId", j.bank_code AS "bankCode", j.workspace_id AS "workspaceId",
                       j.batch_id AS "batchId", j.job_type AS "jobType", j.job_name AS "jobName",
                       j.scenario_code AS "scenarioCode", j.status, j.progress,
                       j.input_params->>'processingMode' AS "processingMode",
                       j.input_params->>'sourceFileName' AS "sourceFileName",
                       j.total_steps AS "totalSteps", j.current_step AS "currentStep",
                       j.created_at AS "createdAt", j.started_at AS "startedAt", j.completed_at AS "completedAt"
                FROM analysis_job j
                """ + where + " ORDER BY CASE j.status WHEN 'RUNNING' THEN 0 WHEN 'PENDING' THEN 1 " +
                "WHEN 'SUCCEEDED' THEN 2 WHEN 'FAILED' THEN 3 WHEN 'CANCELLED' THEN 4 ELSE 5 END, " +
                "j.created_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        StringBuilder summaryWhere = new StringBuilder(" WHERE deleted=false");
        List<Object> summaryArgs = new ArrayList<>();
        if (bankCode != null && !bankCode.isBlank()) { summaryWhere.append(" AND bank_code=?"); summaryArgs.add(bankCode); }
        if (jobType != null && !jobType.isBlank()) { summaryWhere.append(" AND job_type=?"); summaryArgs.add(jobType); }
        if (scenarioCode != null && !scenarioCode.isBlank()) { summaryWhere.append(" AND scenario_code=?"); summaryArgs.add(scenarioCode); }
        List<Map<String, Object>> statusRows = jdbcTemplate.queryForList(
                "SELECT status,COUNT(*) AS count FROM analysis_job" + summaryWhere + " GROUP BY status",
                summaryArgs.toArray());
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String value : List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) statusCounts.put(value, 0L);
        for (Map<String, Object> row : statusRows) {
            statusCounts.put(Objects.toString(row.get("status")), ((Number) row.get("count")).longValue());
        }
        return CommonResult.success(Map.of("records", records, "total", total == null ? 0 : total,
                "pageNum", page, "pageSize", size, "statusCounts", statusCounts));
    }
}
