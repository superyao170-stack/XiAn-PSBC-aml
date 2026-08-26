
package com.datagraph.bank.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cf_risk_event")
public class CfRiskEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String businessId;

    private String caseId;

    private Long caseVersion;

    private String bankCode;

    private String eventName;

    private String eventType;

    private String eventStandardCode;

    private String eventFrameCode;

    private Integer eventFrameVersion;

    private String semanticProfileCode;

    private Integer semanticProfileVersion;

    private String lifecycleCode;

    private Integer lifecycleVersion;

    private String lifecycleState;

    private String factLevel;

    private String definitionBindingStatus;

    private String definitionMatchMethod;

    private BigDecimal definitionMatchConfidence;

    private LocalDateTime eventTime;

    private BigDecimal confidence;

    private String evidenceRefs;

    private String dedupKey;

    private BigDecimal riskScore;

    private BigDecimal eventQualityScore;

    private String qualityBreakdown;

    private String qualityPolicyVersion;

    private String identityResolutionStatus;

    private String canonicalEventId;

    private String riskLevel;

    private String ruleName;

    private Integer subjectCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Boolean deleted;
}
