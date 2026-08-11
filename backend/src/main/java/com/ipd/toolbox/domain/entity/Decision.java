package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 决策记录实体。
 *
 * <p>记录对某一业务对象（需求/缺陷/风险等）形成的正式结论。
 * 可用于留痕是否批准、驳回、转交或临时放行，并支持指向上游版本与时间承诺。
 */
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
