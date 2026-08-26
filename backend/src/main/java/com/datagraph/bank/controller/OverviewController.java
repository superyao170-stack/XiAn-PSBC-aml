package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;

    public OverviewController(JdbcTemplate jdbcTemplate, CurrentUser currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    @GetMapping
    public CommonResult<Map<String, Object>> overview(
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate startDate,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate endDate,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String scenarioCode) {
        StringBuilder filter = new StringBuilder(" WHERE deleted=false");
        List<Object> params = new ArrayList<>();
        if (currentUser.isBankAdmin()) {
            filter.append(" AND bank_code=?");
            params.add(currentUser.requiredBankCode());
        }
        if (startDate != null) {
            filter.append(" AND created_at>=?");
            params.add(startDate.atStartOfDay());
        }
        if (endDate != null) {
            filter.append(" AND created_at<?");
            params.add(endDate.plusDays(1).atStartOfDay());
        }
        if (scenarioCode != null && !scenarioCode.isBlank()) {
            filter.append(" AND scenario_code=?");
            params.add(scenarioCode);
        }
        String where = filter.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseCount", count("SELECT COUNT(*) FROM cf_risk_case" + where, params));
        result.put("pendingReviewCount", count("SELECT COUNT(*) FROM cf_risk_case" + where + " AND case_status='IN_REVIEW'", params));
        result.put("pendingApprovalCount", count("SELECT COUNT(*) FROM cf_risk_case" + where + " AND case_status='PENDING_APPROVAL'", params));
        result.put("highRiskCount", count("SELECT COUNT(*) FROM cf_risk_case" + where + " AND risk_level IN ('HIGH','CRITICAL')", params));
        result.put("statusDistribution", jdbcTemplate.queryForList("""
                SELECT case_status AS "name", COUNT(*) AS "value"
                FROM cf_risk_case %s GROUP BY case_status ORDER BY case_status
                """.formatted(where), params.toArray()));
        result.put("riskDistribution", jdbcTemplate.queryForList("""
                SELECT risk_level AS "name", COUNT(*) AS "value"
                FROM cf_risk_case %s GROUP BY risk_level ORDER BY risk_level
                """.formatted(where), params.toArray()));
        List<Map<String, Object>> recentCases = jdbcTemplate.queryForList("""
                SELECT id AS "caseSequenceId", case_id AS "caseId",
                       source_case_no AS "sourceCaseNo", case_name AS "caseName",
                       scenario_code AS "scenarioCode", risk_level AS "riskLevel",
                       case_status AS "caseStatus", bank_code AS "bankCode", created_at AS "createdAt"
                FROM cf_risk_case %s ORDER BY created_at DESC LIMIT 5
                """.formatted(where), params.toArray());
        result.put("recentCases", recentCases);
        return CommonResult.success(result);
    }

    private Long count(String sql, List<Object> params) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return value == null ? 0L : value;
    }

}
