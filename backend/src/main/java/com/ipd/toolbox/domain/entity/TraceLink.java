package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 追踪关联实体。
 *
 * <p>建立任意两类对象间的关系边（如需求-用例、缺陷-工作项）。
 * 通过 source/target type 与 id 组合构成可追溯图，支撑覆盖率和影响分析。
 */
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
