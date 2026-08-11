package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计事件实体。
 *
 * <p>该表记录关键业务变更（创建、更新、状态变更、权限动作等）前后的关键信息，
 * 作为运营稽核、问题追踪和责任归属的最小事实来源。
 */
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
