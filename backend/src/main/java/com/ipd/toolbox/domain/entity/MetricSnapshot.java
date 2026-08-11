package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指标快照实体。
 *
 * <p>按日期存储项目治理指标的聚合结果（准入率、缺陷与需求完成度等），
 * 与趋势统计和报表联动，支撑可复算基线。
 */
@Data
@TableName("metric_snapshot")
public class MetricSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private LocalDate snapDate;
    private Integer criteriaTotal;
    private Integer criteriaMet;
    private Integer openDefects;
    private Integer reqTotal;
    private Integer reqAccepted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
