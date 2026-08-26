package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.config.OpenApiConfig;
import com.datagraph.bank.service.AmlIntelligenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/aml-intelligence")
public class AmlIntelligenceController {
    private final AmlIntelligenceService service;

    public AmlIntelligenceController(AmlIntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public CommonResult<Map<String, Object>> health() {
        return CommonResult.success(service.health());
    }

    @PostMapping("/text-generation")
    @Operation(
            summary = "2. 单独生成可疑报告",
            description = "根据案例基本信息、客户和交易特征生成两版分析文本及段落审核结果。正常业务流程由 STRUCTURED 任务的 TEXT 步骤调用；该接口用于独立调试或集成。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "basicInfo":{"case_id":"CASE-2026-001","case_name":"可疑资金快进快出"},
                              "customers":[{"entity_id":"CUS-001","customer_name":"张某"}],
                              "transactionFeatures":{"summary":"资金到账后短时间内分散转出","transaction_count":18},
                              "knowledgeBase":{}
                            }
                            """))))
    public CommonResult<Map<String, Object>> generateText(
            @RequestBody Map<String, Object> payload) {
        return CommonResult.success(service.generateText(payload));
    }

    @PostMapping("/case-similarity")
    @Operation(
            summary = "4. 单独执行案例相似度匹配",
            description = "以 queryCase 对 historyCases 进行召回、重排和 GED 匹配，返回相似度排序。正常业务流程由 STRUCTURED 任务的 ANALYSIS 步骤调用。",
            tags = OpenApiConfig.STRUCTURED_CASE_TAG,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "queryCase":{"basic_info":{"case_id":"CASE-NEW"},"customers":[],"accounts":[],"other_entities":[],"events":[],"relationships":[],"evidences":[]},
                              "historyCases":[{"basic_info":{"case_id":"CASE-HISTORY-001"},"customers":[],"accounts":[],"other_entities":[],"events":[],"relationships":[],"evidences":[]}],
                              "parameters":{"gedCandidateLimit":25,"embeddingRecallThreshold":0.35}
                            }
                            """))))
    public CommonResult<Map<String, Object>> matchCases(
            @RequestBody Map<String, Object> payload) {
        return CommonResult.success(service.matchCases(payload));
    }
}
