package com.datagraph.bank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger/OpenAPI entry point for the two primary case-processing workflows.
 */
@Configuration
public class OpenApiConfig {
    public static final String STRUCTURED_CASE_TAG = "结构化案例识别流程";
    public static final String CASE_REVIEW_TAG = "案例复核审批流程";
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI bankGraphOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DataGraph Bank 后端接口文档")
                        .version("v1")
                        .description("""
                                面向银行风险案例处理的 REST API。核心流程：

                                1. 结构化案例识别：数据上传 → 可疑报告生成/复用 → 框架抽取 → 相似度匹配 → 图谱展示；
                                2. 案例流转：案例列表 → 提交复核 → 复核 → 审批。

                                业务响应统一使用 `CommonResult` 包装。请先调用登录接口取得 JWT，再在 Swagger UI 的 Authorize 中填写 token（无需手工添加 Bearer 前缀）。
                                """)
                        .contact(new Contact().name("DataGraph Bank")))
                .tags(List.of(
                        new Tag().name(STRUCTURED_CASE_TAG).description(
                                "数据上传、识别任务执行、可疑报告、框架抽取、相似度匹配和案例图谱查询。"),
                        new Tag().name(CASE_REVIEW_TAG).description(
                                "状态主链：DRAFT → IN_REVIEW → PENDING_APPROVAL → APPROVED；复核或审批拒绝后进入 REJECTED。")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 JWT access token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public GroupedOpenApi structuredCaseWorkflowApi() {
        return GroupedOpenApi.builder()
                .group("01-结构化案例识别流程")
                .pathsToMatch(
                        "/api/v1/auth/login",
                        "/api/v1/analysis/structured-case-files",
                        "/api/v1/analysis/structured-case-batch-file",
                        "/api/v1/analysis/jobs/**",
                        "/api/v1/aml-intelligence/**",
                        "/api/v1/graph/case-subgraph")
                .build();
    }

    @Bean
    public GroupedOpenApi caseReviewWorkflowApi() {
        return GroupedOpenApi.builder()
                .group("02-案例复核审批流程")
                .pathsToMatch("/api/v1/auth/login", "/api/v1/cases", "/api/v1/cases/*", "/api/v1/cases/*/submit",
                        "/api/v1/cases/*/review", "/api/v1/cases/*/approve", "/api/v1/cases/*/workflow")
                .build();
    }
}
