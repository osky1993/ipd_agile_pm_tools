package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基线记录实体。
 *
 * <p>用于记录评审或手工建立的基线快照（含来源、阶段门、决策关联）。
 * 常用于冻结时点比对与回滚/复盘依据。
 */
@Data
@TableName("baseline")
public class Baseline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    /** DCP=评审通过自动固化；MANUAL=手动建立 */
    private String source;
    private Long stageGateId;
    private Long decisionId;
    private Integer itemCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
