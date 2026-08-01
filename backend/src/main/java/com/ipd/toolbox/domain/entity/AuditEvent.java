package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_event")
public class AuditEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String entityType;
    private Long entityId;
    private String action;
    private Long actorId;
    private String summary;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime at;
}
