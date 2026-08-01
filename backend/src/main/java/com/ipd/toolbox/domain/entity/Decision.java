package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("decision")
public class Decision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private String decisionType;
    private String subjectType;
    private Long subjectId;
    private String conclusion;
    private String reason;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String snapshot;
    private Long linkedRiskId;
    private LocalDate commitmentDue;
    private Long prevDecisionId;
}
