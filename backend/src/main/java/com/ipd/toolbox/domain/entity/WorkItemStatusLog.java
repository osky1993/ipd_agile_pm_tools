package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作项状态流转日志。
 *
 * <p>记录状态变迁的来源、去向、操作者与原因文本，
 * 为状态机事件追踪、异常复盘和审计抽样提供事实记录。
 */
@Data
@TableName("work_item_status_log")
public class WorkItemStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workItemId;
    private String fromStatus;
    private String toStatus;
    private Long actorId;
    private String reason;
    private LocalDateTime at;
}
