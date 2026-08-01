package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("work_item")
public class WorkItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private Long productVersionId;
    private String type;
    private String title;
    private String description;
    private String status;
    private Long ownerId;
    private String priority;
    private Long iterationId;
    private LocalDate baselineDate;
    private LocalDate forecastDate;
    private String acceptanceCriteria;
    private String estimate;
    private Long acceptedBy;
    private LocalDateTime acceptedAt;
    /** 类型差异字段，存 JSON 字符串（缺陷 severity、风险 mitigation/due、变更 impact 等）。 */
    private String extFields;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
