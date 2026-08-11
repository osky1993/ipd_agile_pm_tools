package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 阶段门准入标准实体。
 *
 * <p>用于定义某阶段门的判定项（判定类型、证据要求、验收责任人、豁免信息等），
 * 并支持红线标识、是否 readiness 条目、以及到期与审阅结论。
 */
@Data
@TableName("gate_criterion")
public class GateCriterion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private Long stageGateId;
    private String domain;
    private String criterion;
    private String judgeType;
    private Long ownerId;
    private String status;
    private String evidenceReq;
    private Integer isRedline;
    private Long linkedRiskId;
    private String reviewConclusion;
    private String waiverReason;
    private Long waiverBy;
    private LocalDate waiverDue;
    private LocalDate planDate;
    private LocalDate forecastDate;
    private Integer isReadiness;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
