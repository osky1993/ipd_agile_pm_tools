package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 产品版本实体。
 *
 * <p>记录特定项目下的版本信息，包括编码、型号、版本号及计划/实际发布时间，
 * 是发布闭环和度量归因的基础版本维度。
 */
@Data
@TableName("product_version")
public class ProductVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private String model;
    private String versionNo;
    private String baseline;
    private LocalDate planReleaseDate;
    private LocalDate actualReleaseDate;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
