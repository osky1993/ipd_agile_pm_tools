package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
