package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trace_link")
public class TraceLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String sourceType;
    private Long sourceId;
    private String targetType;
    private Long targetId;
    private String relation;
    private Long createdBy;
    private LocalDateTime createdAt;
}
