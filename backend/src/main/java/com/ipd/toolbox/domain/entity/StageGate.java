package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 阶段门实体。
 *
 * <p>描述项目流程中的阶段、门与计划/预估时间，
 * 与 gate criterion/decision 等记录联动，支撑阶段门评审与流程治理。
 */
@Data
@TableName("stage_gate")
public class StageGate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private String stageName;
    private String gateName;
    private Integer seq;
    private LocalDate planDate;
    private LocalDate forecastDate;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
