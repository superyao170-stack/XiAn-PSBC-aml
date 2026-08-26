package com.datagraph.bank.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reliable PostgreSQL -> TuGraph projection for frozen core-chain snapshots.
 * A failed graph write never rolls back the relational snapshot; the outbox is
 * retried with a bounded backoff and the snapshot remains queryable.
 */
@Service
public class CoreChainProjectionWorker {
    private static final Logger log = LoggerFactory.getLogger(CoreChainProjectionWorker.class);
    private final JdbcTemplate jdbc;
    private final TuGraphStructuredWriter writer;

    public CoreChainProjectionWorker(JdbcTemplate jdbc, TuGraphStructuredWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    @Scheduled(fixedDelayString = "${core-chain.projection-delay-ms:5000}")
    @Transactional
    public void projectPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT event_id,case_id,chain_id,retry_count
            FROM case_core_chain_outbox
            WHERE status IN ('PENDING','FAILED')
              AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP)
              AND retry_count<10
            ORDER BY created_at LIMIT 5 FOR UPDATE SKIP LOCKED
            """);
        for (Map<String, Object> row : rows) {
            String eventId = Objects.toString(row.get("event_id"));
            String caseId = Objects.toString(row.get("case_id"));
            String chainId = Objects.toString(row.get("chain_id"));
            try {
                jdbc.update("UPDATE case_core_chain_outbox SET status='PROJECTING' WHERE event_id=?",
                        eventId);
                writer.projectCase(caseId);
                jdbc.update("""
                    UPDATE case_core_chain_outbox
                    SET status='PROJECTED',projected_at=CURRENT_TIMESTAMP,last_error=NULL
                    WHERE event_id=?
                    """, eventId);
                jdbc.update("""
                    UPDATE case_core_chain_snapshot SET projection_status='PROJECTED'
                    WHERE chain_id=?
                    """, chainId);
            } catch (Exception ex) {
                log.warn("Core-chain projection failed for case {} chain {} event {}",
                        caseId, chainId, eventId, ex);
                jdbc.update("""
                    UPDATE case_core_chain_outbox
                    SET status='FAILED',retry_count=retry_count+1,
                        next_retry_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',
                        last_error=?
                    WHERE event_id=?
                    """, abbreviate(ex.getMessage()), eventId);
                jdbc.update("""
                    UPDATE case_core_chain_snapshot SET projection_status='FAILED'
                    WHERE chain_id=?
                    """, chainId);
            }
        }
    }

    private String abbreviate(String value) {
        if (value == null) return "Unknown projection error";
        return value.length() <= 1800 ? value : value.substring(0, 1800);
    }
}
