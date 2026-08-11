package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 性能快照实体。
 *
 * <p>记录某一性能指标在指定日期的观测值，
 * 便于容量规划、异常回归定位与历史曲线分析。
 */
@Data
@TableName("perf_snapshot")
public class PerfSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private LocalDate snapDate;
    private String metricKey;
    private BigDecimal value;
}
