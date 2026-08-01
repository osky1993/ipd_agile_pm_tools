package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
