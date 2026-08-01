package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

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
