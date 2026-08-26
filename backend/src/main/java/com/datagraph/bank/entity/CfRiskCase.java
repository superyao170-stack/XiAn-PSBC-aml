
package com.datagraph.bank.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cf_risk_case")
public class CfRiskCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String caseId;

    private String caseName;

    private String sourceCaseNo;

    private String description;

    private Long caseVersion;

    private String bankCode;

    private Long institutionId;

    private Long orgId;

    private Long workspaceId;

    private String scenarioCode;

    private String scenarioVersion;

    private String caseSource;

    private String caseType;

    private String caseStatus;

    private String businessDomain;

    private String businessCaseType;

    private String reportingDirection;

    private String triggerPoint;

    private String urgencyLevel;

    private LocalDateTime reportedAt;

    private String businessCaseStatus;

    private String businessRiskLevel;

    private String suspectedCrimeType;

    private String suspiciousTransactionFeatureCode;

    private String disposalMeasure;

    private BigDecimal riskScore;

    private String riskLevel;

    private Integer subjectCount;

    private Integer transactionCount;

    private BigDecimal totalAmount;

    private String graphSnapshotId;

    private String graphSnapshotSha256;

    private String deploymentConfigSnapshotId;

    private String effectiveConfigSnapshotId;

    private String executionPackageId;

    private String ownerAnalyst;

    private String reviewer;

    private String approver;

    private String structDecision;

    private String textDecision;

    private Boolean decisionConflict;

    private String conflictDetails;

    private Long supersedesCaseVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime closedAt;

    private Boolean deleted;

    @TableField(exist = false)
    private Integer eventCount;
    @TableField(exist = false)
    private Integer signalCount;
    @TableField(exist = false)
    private String patternName;
    @TableField(exist = false)
    private Integer patternProductCount;
    @TableField(exist = false)
    private Integer matterProductCount;
    @TableField(exist = false)
    private Integer atomicMatterProductCount;
    @TableField(exist = false)
    private Integer riskProductCount;
    @TableField(exist = false)
    private Integer techniqueProductCount;
    @TableField(exist = false)
    private Integer productSnapshotCount;
    @TableField(exist = false)
    private String productionStatus;
    @TableField(exist = false)
    private String recognitionMode;
    @TableField(exist = false)
    private LocalDateTime lastProducedAt;
}
