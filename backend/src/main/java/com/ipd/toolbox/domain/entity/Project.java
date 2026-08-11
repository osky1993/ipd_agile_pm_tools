package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目实体。
 *
 * <p>承载项目的最小主数据（编码、名称、目标、负责人、生命周期状态），
 * 为工作项、阶段门、证据与决策等全部领域模型提供归属维度。
 */
@Data
@TableName("project")
public class Project {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String goal;
    private Long managerId;
    private String lifecycleStatus;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
