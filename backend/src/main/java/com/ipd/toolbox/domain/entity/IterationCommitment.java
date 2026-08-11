package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 迭代承诺快照实体。
 *
 * <p>记录工作项在某次迭代分配时的预估值等快照，
 * 便于后续对比承诺变更与交付偏差，支持燃尽和负荷回溯分析。
 */
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
