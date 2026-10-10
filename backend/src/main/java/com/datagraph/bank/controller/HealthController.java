package com.datagraph.bank.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight probes for Nginx, systemd and container orchestration. */
@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping({"/healthz", "/actuator/health"})
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", OffsetDateTime.now().toString());
        result.put("service", "bankgraph-backend");
        result.put("status", "UP");
        result.put("database", "UP");
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(result);
        } catch (Exception exception) {
            result.put("status", "DOWN");
            result.put("database", "DOWN");
            result.put("error", "database check failed");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> ready() {
        return health();
    }
}
