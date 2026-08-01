package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
