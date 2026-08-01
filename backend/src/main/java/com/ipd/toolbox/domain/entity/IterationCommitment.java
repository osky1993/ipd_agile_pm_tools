package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("iteration_commitment")
public class IterationCommitment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long iterationId;
    private Long workItemId;
    private String estimateSnap;
    private LocalDateTime committedAt;
}
