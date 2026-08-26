
package com.datagraph.bank.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("risk_signal")
public class RiskSignal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String signalId;

    private String businessId;

    private String bankCode;

    private Long institutionId;

    private Long workspaceId;

    private String scenarioCode;

    private String scenarioVersion;

    private String pipelineVersion;

    private String algorithmBindingVersion;

    private String deploymentConfigSnapshotId;

    private String effectiveConfigSnapshotId;

    private String executionPackageId;

    private String signalType;

    private String sourceRefType;

    private String sourceRefId;

    private Long sourceTransactionId;

    private Long sourceBatchId;

    private String algorithmId;

    private String algorithmVersion;

    private String algorithmEndpoint;

    private BigDecimal score;

    private String decision;

    private String[] reasonCodes;

    private String recommendedAction;

    private String policyVersion;

    private String modelVersion;

    private String inputDataVersion;

    private String inputDataHash;

    private String contribution;

    private BigDecimal fusionWeight;

    private String parentSignalId;

    private Boolean isFused;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private String createdBy;
}
