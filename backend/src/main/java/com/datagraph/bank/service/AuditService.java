package com.datagraph.bank.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void login(String username, String bankCode, String ip, String userAgent,
                      boolean success, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO audit_login_log
                (log_id, username, bank_code, ip_address, user_agent, login_type, success, error_message)
                VALUES (?, ?, ?, ?, ?, 'PASSWORD', ?, ?)
                """, "LOGIN-" + UUID.randomUUID(), username, bankCode, ip, truncate(userAgent, 255),
                success, truncate(errorMessage, 1000));
    }

    public void operation(String username, String bankCode, String operationType, String module,
                          String description, String ip, String userAgent, long elapsed,
                          boolean success, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO audit_operation_log
                (log_id, bank_code, username, operation_type, operation_module, operation_desc,
                 ip_address, user_agent, execution_time_ms, success, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "OP-" + UUID.randomUUID(), bankCode, username, operationType, module,
                truncate(description, 512), ip, truncate(userAgent, 255), elapsed, success,
                truncate(errorMessage, 1000));
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
