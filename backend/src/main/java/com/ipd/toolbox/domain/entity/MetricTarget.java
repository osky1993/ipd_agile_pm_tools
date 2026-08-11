package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标目标实体。
 *
 * <p>定义项目级指标阈值（metricKey 与 targetValue），
 * 用于与实际快照对比判定达成状态，并支持阈值版本化调整。
 */
@Data
@TableName("metric_target")
public class MetricTarget {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String metricKey;
    private BigDecimal targetValue;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
