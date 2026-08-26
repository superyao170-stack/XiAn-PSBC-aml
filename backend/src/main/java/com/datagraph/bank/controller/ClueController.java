package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.service.GraphClueAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/clues")
public class ClueController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final GraphClueAnalysisService graphClueAnalysisService;
    private final ObjectMapper objectMapper;

    public ClueController(JdbcTemplate jdbc, CurrentUser currentUser,
                          GraphClueAnalysisService graphClueAnalysisService,
                          ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.graphClueAnalysisService = graphClueAnalysisService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public CommonResult<Map<String, Object>> list(
            @RequestParam(required = false) String clueType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String analysisRunId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        StringBuilder where = new StringBuilder(" WHERE deleted=false");
        List<Object> args = new ArrayList<>();
        if (currentUser.isBankAdmin()) {
            where.append(" AND bank_code = ?");
            args.add(currentUser.requiredBankCode());
        }
        if (clueType != null && !clueType.isBlank()) {
            where.append(" AND clue_type = ?");
            args.add(clueType);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (analysisRunId != null && !analysisRunId.isBlank()) {
            where.append(" AND analysis_run_id = ?");
            args.add(analysisRunId);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM risk_clue" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT id, clue_id, bank_code, institution_id, case_id, clue_type,
                       algorithm_code, algorithm_version, source_type, analysis_run_id, evidence_subgraph_ref, confidence,
                       explanation,
                       COALESCE(to_json(related_case_ids)::text, '[]') AS related_case_ids_json,
                       COALESCE(to_json(related_subject_ids)::text, '[]') AS related_subject_ids_json,
                       status, created_at, updated_at, created_by
                FROM risk_clue
                """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        records.forEach(this::normalizeArrays);
        return CommonResult.success(Map.of(
                "records", records, "total", total == null ? 0 : total,
                "pageNum", page, "pageSize", size));
    }

    @GetMapping("/runs")
    public CommonResult<List<Map<String,Object>>> runs(@RequestParam(required = false) String bankCode) {
        String scopedBank = currentUser.scopedBankCode(bankCode);
        String where = scopedBank == null ? "" : " WHERE bank_code=?";
        Object[] args = scopedBank == null ? new Object[0] : new Object[]{scopedBank};
        return CommonResult.success(jdbc.queryForList("""
                SELECT run_id,bank_code,scenario_code,start_time,end_time,input_case_count,
                       scanned_account_nodes,scanned_event_nodes,generated_clues,status,error_message,
                       created_by,created_at,completed_at
                FROM risk_clue_analysis_run
                """ + where + " ORDER BY created_at DESC LIMIT 50", args));
    }

    @GetMapping("/{clueId}")
    public CommonResult<Map<String,Object>> detail(@PathVariable String clueId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.clue_id, c.bank_code, c.institution_id, c.case_id, c.clue_type,
                       c.algorithm_code, c.algorithm_version, c.source_type, c.analysis_run_id, c.evidence_subgraph_ref, c.confidence,
                       c.explanation,
                       COALESCE(to_json(related_case_ids)::text, '[]') AS related_case_ids_json,
                       COALESCE(to_json(related_subject_ids)::text, '[]') AS related_subject_ids_json,
                       c.status, c.created_at, c.updated_at, c.created_by, c.deleted,
                       r.scenario_code AS analysis_scenario_code,r.start_time AS analysis_start_time,
                       r.end_time AS analysis_end_time,r.input_case_count,r.scanned_account_nodes,
                       r.scanned_event_nodes,r.generated_clues,r.status AS analysis_status
                FROM risk_clue c LEFT JOIN risk_clue_analysis_run r ON r.run_id=c.analysis_run_id
                WHERE c.clue_id=? AND c.deleted=false
                """, clueId);
        if (rows.isEmpty()) return CommonResult.notFound("线索不存在");
        currentUser.requireAccessToBank(Objects.toString(rows.get(0).get("bank_code"), ""));
        normalizeArrays(rows.get(0));
        return CommonResult.success(rows.get(0));
    }

    @PostMapping("/analyze")
    public CommonResult<Map<String,Object>> analyze(@RequestBody(required = false) Map<String,Object> body) {
        String requested = body == null ? null : Objects.toString(body.get("bankCode"), null);
        String bankCode = currentUser.scopedBankCode(requested);
        if (bankCode == null || bankCode.isBlank()) return CommonResult.badRequest("请选择分析银行");
        currentUser.requireAccessToBank(bankCode);
        return CommonResult.success(graphClueAnalysisService.analyze(bankCode, currentUser.username(), body == null ? Map.of() : body));
    }

    @PostMapping
    public CommonResult<Map<String,Object>> create(@RequestBody Map<String,Object> body) {
        String requested = Objects.toString(body.get("bankCode"), null);
        String bankCode = currentUser.scopedBankCode(requested);
        if (bankCode == null || bankCode.isBlank()) return CommonResult.badRequest("银行不能为空");
        currentUser.requireAccessToBank(bankCode);
        String clueType = Objects.toString(body.get("clueType"), "");
        String explanation = Objects.toString(body.get("explanation"), "");
        if (clueType.isBlank() || explanation.isBlank()) return CommonResult.badRequest("线索类型和说明不能为空");
        List<String> relatedCaseIds = stringList(body.get("relatedCaseIds"));
        if (relatedCaseIds.isEmpty()) return CommonResult.badRequest("手工线索至少关联一个输入案例");
        if (!caseIdsBelongToBank(relatedCaseIds, bankCode)) return CommonResult.badRequest("关联案例不存在或不属于当前银行");
        List<String> relatedSubjectIds = stringList(body.get("relatedSubjectIds"));
        String sourceRef = Objects.toString(body.get("sourceRef"), "").trim();
        String clueId = "CLUE-MANUAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        jdbc.update("""
                INSERT INTO risk_clue(clue_id,bank_code,clue_type,algorithm_code,algorithm_version,source_type,
                  evidence_subgraph_ref,confidence,explanation,related_case_ids,related_subject_ids,
                  status,created_by,deleted,updated_at)
                VALUES (?,?,?,'MANUAL','1.0.0','MANUAL',?,?,?,?,?,'CANDIDATE',?,false,CURRENT_TIMESTAMP)
                """, clueId, bankCode, clueType, sourceRef, confidence, explanation,
                relatedCaseIds.toArray(String[]::new), relatedSubjectIds.toArray(String[]::new), currentUser.username());
        return detail(clueId);
    }

    @PutMapping("/{clueId}")
    public CommonResult<Map<String,Object>> update(@PathVariable String clueId, @RequestBody Map<String,Object> body) {
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT bank_code,status FROM risk_clue WHERE clue_id=? AND deleted=false", clueId);
        if (rows.isEmpty()) return CommonResult.notFound("线索不存在");
        currentUser.requireAccessToBank(Objects.toString(rows.get(0).get("bank_code"), ""));
        if (!"CANDIDATE".equals(rows.get(0).get("status"))) return CommonResult.error(409, "仅候选线索可以编辑");
        String type = Objects.toString(body.get("clueType"), "");
        String explanation = Objects.toString(body.get("explanation"), "");
        if (type.isBlank() || explanation.isBlank()) return CommonResult.badRequest("线索类型和说明不能为空");
        List<String> relatedCaseIds = stringList(body.get("relatedCaseIds"));
        if (relatedCaseIds.isEmpty()) return CommonResult.badRequest("线索至少关联一个输入案例");
        String bankCode = Objects.toString(rows.get(0).get("bank_code"), "");
        if (!caseIdsBelongToBank(relatedCaseIds, bankCode)) return CommonResult.badRequest("关联案例不存在或不属于当前银行");
        List<String> relatedSubjectIds = stringList(body.get("relatedSubjectIds"));
        String sourceRef = Objects.toString(body.get("sourceRef"), "").trim();
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        jdbc.update("""
                UPDATE risk_clue SET clue_type=?,confidence=?,explanation=?,evidence_subgraph_ref=?,
                  related_case_ids=?,related_subject_ids=?,updated_at=CURRENT_TIMESTAMP
                WHERE clue_id=?
                """, type, confidence, explanation, sourceRef, relatedCaseIds.toArray(String[]::new),
                relatedSubjectIds.toArray(String[]::new), clueId);
        return detail(clueId);
    }

    @DeleteMapping("/{clueId}")
    public CommonResult<Void> delete(@PathVariable String clueId) {
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT bank_code,status FROM risk_clue WHERE clue_id=? AND deleted=false", clueId);
        if (rows.isEmpty()) return CommonResult.notFound("线索不存在");
        currentUser.requireAccessToBank(Objects.toString(rows.get(0).get("bank_code"), ""));
        if ("MERGED".equals(rows.get(0).get("status"))) return CommonResult.error(409, "已合并到案例的线索不能删除");
        jdbc.update("UPDATE risk_clue SET deleted=true,updated_at=CURRENT_TIMESTAMP WHERE clue_id=?", clueId);
        return CommonResult.success(null);
    }

    @PatchMapping("/{clueId}/status")
    public CommonResult<Void> updateStatus(@PathVariable String clueId,
                                            @RequestBody Map<String, Object> body) {
        String target = Objects.toString(body.get("status"), "");
        if (!Set.of("CANDIDATE", "MERGED", "DISMISSED", "SUPERSEDED").contains(target)) {
            return CommonResult.badRequest("Invalid clue status");
        }
        String sql = "UPDATE risk_clue SET status = ?, updated_at=CURRENT_TIMESTAMP WHERE clue_id = ? AND deleted=false";
        List<Object> args = new ArrayList<>(List.of(target, clueId));
        if (currentUser.isBankAdmin()) {
            sql += " AND bank_code = ?";
            args.add(currentUser.requiredBankCode());
        }
        int changed = jdbc.update(sql, args.toArray());
        return changed == 0 ? CommonResult.notFound("Clue does not exist") : CommonResult.success(null);
    }

    @PostMapping("/{clueId}/merge/{caseId}")
    @Transactional
    public CommonResult<Void> merge(@PathVariable String clueId, @PathVariable String caseId) {
        List<Map<String, Object>> clues = jdbc.queryForList(
                "SELECT bank_code FROM risk_clue WHERE clue_id = ?", clueId);
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT bank_code FROM cf_risk_case WHERE case_id = ?", caseId);
        if (clues.isEmpty() || cases.isEmpty()) return CommonResult.notFound("Clue or case does not exist");
        String clueBank = Objects.toString(clues.get(0).get("bank_code"), "");
        String caseBank = Objects.toString(cases.get(0).get("bank_code"), "");
        currentUser.requireAccessToBank(clueBank);
        currentUser.requireAccessToBank(caseBank);
        if (!clueBank.equals(caseBank)) return CommonResult.forbidden();
        jdbc.update("UPDATE risk_clue SET case_id = ?, status = 'MERGED',updated_at=CURRENT_TIMESTAMP WHERE clue_id = ?", caseId, clueId);
        return CommonResult.success(null);
    }

    @SuppressWarnings("unchecked")
    private void normalizeArrays(Map<String, Object> row) {
        row.put("related_case_ids", parseArray(row.remove("related_case_ids_json")));
        row.put("related_subject_ids", parseArray(row.remove("related_subject_ids_json")));
    }

    private List<String> parseArray(Object json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json.toString(), List.class);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(item -> Objects.toString(item, "").trim())
                .filter(item -> !item.isBlank()).distinct().limit(5000).toList();
    }

    private boolean caseIdsBelongToBank(List<String> caseIds, String bankCode) {
        String placeholders = String.join(",", Collections.nCopies(caseIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(bankCode);
        args.addAll(caseIds);
        Long count = jdbc.queryForObject("SELECT count(*) FROM cf_risk_case WHERE deleted=false AND bank_code=? AND case_id IN (" + placeholders + ")",
                Long.class, args.toArray());
        return count != null && count == caseIds.size();
    }
}
