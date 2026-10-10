package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.common.response.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only audit log queries for system administrators. */
@RestController
@RequestMapping("/api/v1/system/audit")
public class AuditLogController {
    private final JdbcTemplate jdbcTemplate;

    public AuditLogController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/operations")
    public CommonResult<PageResult<Map<String, Object>>> operations(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Boolean success) {
        return CommonResult.success(query("audit_operation_log", pageNum, pageSize, username, module, success,
                "id, log_id AS \"logId\", username, bank_code AS \"bankCode\", operation_type AS \"operationType\", "
                        + "operation_module AS \"operationModule\", operation_desc AS \"operationDesc\", ip_address AS \"ipAddress\", "
                        + "execution_time_ms AS \"executionTimeMs\", success, error_message AS \"errorMessage\", created_at AS \"createdAt\""));
    }

    @GetMapping("/logins")
    public CommonResult<PageResult<Map<String, Object>>> logins(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Boolean success) {
        return CommonResult.success(query("audit_login_log", pageNum, pageSize, username, null, success,
                "id, log_id AS \"logId\", username, bank_code AS \"bankCode\", ip_address AS \"ipAddress\", "
                        + "user_agent AS \"userAgent\", login_type AS \"loginType\", success, error_message AS \"errorMessage\", "
                        + "created_at AS \"createdAt\""));
    }

    private PageResult<Map<String, Object>> query(String table, int requestedPage, int requestedSize,
                                                   String username, String module, Boolean success, String columns) {
        int page = Math.max(requestedPage, 1);
        int size = List.of(20, 50, 100).contains(requestedSize) ? requestedSize : 20;
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" AND username ILIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (module != null && !module.isBlank()) {
            where.append(" AND operation_module=?");
            args.add(module.trim());
        }
        if (success != null) {
            where.append(" AND success=?");
            args.add(success);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT " + columns + " FROM " + table + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return PageResult.of(records, total == null ? 0 : total, page, size);
    }
}
