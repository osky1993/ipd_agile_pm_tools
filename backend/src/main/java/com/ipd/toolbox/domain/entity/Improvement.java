package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("improvement")
public class Improvement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String code;
    private String metricKey;
    private String title;
    private String measure;
    private BigDecimal baselineValue;
    private BigDecimal targetValue;
    private BigDecimal resultValue;
    private String conclusion;
    private LocalDate dueDate;
    /** OPEN → DOING → DONE → VERIFIED */
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
