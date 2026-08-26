package com.datagraph.bank.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physically removes an analysis job and the business assets owned exclusively
 * by that job. Input batches and uploaded source assets are deliberately kept.
 */
@Service
public class AnalysisJobDeletionService {
    private final JdbcTemplate jdbcTemplate;
    private final TuGraphStructuredWriter graphWriter;

    public AnalysisJobDeletionService(JdbcTemplate jdbcTemplate,
                                      TuGraphStructuredWriter graphWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.graphWriter = graphWriter;
    }

    @Transactional
    public Map<String, Object> deleteJob(String jobId) {
        Map<String, Object> job = jdbcTemplate.queryForMap("""
                SELECT job_id, status
                FROM analysis_job
                WHERE job_id=?
                FOR UPDATE
                """, jobId);
        String status = String.valueOf(job.get("status"));
        if (!List.of("PENDING", "SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw new IllegalStateException("运行中的任务不能删除");
        }

        List<String> exclusiveCaseIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT rel.case_id
                FROM case_analysis_job_rel rel
                WHERE rel.job_id=?
                  AND NOT EXISTS (
                    SELECT 1 FROM case_analysis_job_rel other
                    WHERE other.case_id=rel.case_id AND other.job_id<>rel.job_id
                  )
                """, String.class, jobId);
        List<String> sharedCaseIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT rel.case_id
                FROM case_analysis_job_rel rel
                WHERE rel.job_id=?
                  AND EXISTS (
                    SELECT 1 FROM case_analysis_job_rel other
                    WHERE other.case_id=rel.case_id AND other.job_id<>rel.job_id
                  )
                """, String.class, jobId);
        List<String> candidateSignalIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT rel.signal_id
                FROM risk_signal_analysis_job_rel rel
                WHERE rel.job_id=?
                  AND NOT EXISTS (
                    SELECT 1 FROM risk_signal_analysis_job_rel other
                    WHERE other.signal_id=rel.signal_id AND other.job_id<>rel.job_id
                  )
                """, String.class, jobId);

        // Delete the graph first. A graph failure aborts the relational transaction,
        // so the API never reports success while a case subgraph is still present.
        for (String caseId : exclusiveCaseIds) {
            graphWriter.removeCaseSubgraph(caseId);
            deleteCase(caseId);
        }

        int deletedSignals = 0;
        for (String signalId : candidateSignalIds) {
            Integer remainingCaseRefs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM case_signal_rel WHERE signal_id=?",
                    Integer.class, signalId);
            if (remainingCaseRefs != null && remainingCaseRefs == 0) {
                deleteSignal(signalId);
                deletedSignals++;
            }
        }

        jdbcTemplate.update("DELETE FROM structured_pattern_label WHERE job_id=?", jobId);
        jdbcTemplate.update("DELETE FROM algorithm_execution_log WHERE job_id=?", jobId);
        jdbcTemplate.update("""
                DELETE FROM event_outbox
                WHERE aggregate_id=? AND aggregate_type IN ('ANALYSIS_JOB','AnalysisJob')
                """, jobId);
        int deletedJobs = jdbcTemplate.update("DELETE FROM analysis_job WHERE job_id=?", jobId);
        if (deletedJobs != 1) {
            throw new IllegalStateException("任务删除失败，请刷新后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("deletedJobCount", deletedJobs);
        result.put("deletedCaseCount", exclusiveCaseIds.size());
        result.put("deletedSignalCount", deletedSignals);
        result.put("retainedSharedCaseCount", sharedCaseIds.size());
        result.put("retainedInputBatch", true);
        return result;
    }

    private void deleteCase(String caseId) {
        jdbcTemplate.update("""
                DELETE FROM workflow_step
                WHERE workflow_id IN (
                  SELECT workflow_id FROM workflow_instance WHERE case_id=?
                )
                """, caseId);
        jdbcTemplate.update("""
                DELETE FROM technique_occurrence_event_rel
                WHERE occurrence_id IN (
                  SELECT occurrence_id FROM technique_occurrence WHERE case_id=?
                ) OR event_id IN (
                  SELECT event_id FROM cf_risk_event WHERE case_id=?
                )
                """, caseId, caseId);
        jdbcTemplate.update("""
                DELETE FROM case_matter_claim
                WHERE matter_id IN (
                  SELECT matter_id FROM case_matter_explanation WHERE case_id=?
                )
                """, caseId);
        for (String table : List.of(
                "case_core_chain_knowledge_ref",
                "case_core_chain_narrative_ref",
                "case_core_chain_outbox",
                "case_core_chain_node",
                "case_core_chain_edge")) {
            jdbcTemplate.update("""
                    DELETE FROM %s
                    WHERE chain_id IN (
                      SELECT chain_id FROM case_core_chain_snapshot WHERE case_id=?
                    )
                    """.formatted(table), caseId);
        }

        // Dependent hypotheses must be removed before their parent hypothesis.
        jdbcTemplate.update("DELETE FROM alternative_explanation WHERE case_id=?", caseId);
        jdbcTemplate.update("DELETE FROM investigation_hypothesis WHERE case_id=?", caseId);
        jdbcTemplate.update("DELETE FROM case_event_relation WHERE case_id=?", caseId);

        for (String table : List.of(
                "case_core_chain_snapshot",
                "case_explanation_snapshot",
                "risk_event_hypothesis",
                "behavior_pattern_occurrence",
                "case_review_suggestion",
                "case_matter_explanation",
                "attack_path_hypothesis",
                "pattern_match",
                "technique_occurrence",
                "strategy_replay_record",
                "graph_snapshot",
                "risk_clue",
                "workflow_instance",
                "case_approval_record",
                "case_review_record",
                "case_signal_rel",
                "cf_risk_event")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE case_id=?", caseId);
        }
        for (String table : List.of(
                "case_core_chain_knowledge_ref",
                "case_core_chain_narrative_ref",
                "case_core_chain_node",
                "case_core_chain_outbox")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE case_id=?", caseId);
        }
        jdbcTemplate.update("DELETE FROM case_analysis_job_rel WHERE case_id=?", caseId);
        jdbcTemplate.update("DELETE FROM cf_risk_case WHERE case_id=?", caseId);
    }

    private void deleteSignal(String signalId) {
        jdbcTemplate.update("DELETE FROM risk_signal_review_record WHERE signal_id=?", signalId);
        jdbcTemplate.update("DELETE FROM case_signal_rel WHERE signal_id=?", signalId);
        jdbcTemplate.update("DELETE FROM entity_signal_ref WHERE signal_id=?", signalId);
        jdbcTemplate.update("DELETE FROM text_risk_signal WHERE signal_id=?", signalId);
        jdbcTemplate.update("DELETE FROM risk_signal_analysis_job_rel WHERE signal_id=?", signalId);
        jdbcTemplate.update("DELETE FROM risk_signal WHERE signal_id=?", signalId);
    }
}
