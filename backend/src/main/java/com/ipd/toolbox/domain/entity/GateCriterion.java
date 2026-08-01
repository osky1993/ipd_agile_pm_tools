package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
