package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据附件实体。
 *
 * <p>保存上传文件在系统中的元数据（名称、路径、指纹、大小、类型）与逻辑归属。
 * 通过 category 区分正式证据与展示附件，支持合规与追溯。
 */
@Data
@TableName("evidence")
public class Evidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private String fileName;
    private String filePath;
    private String sha256;
    private Long sizeBytes;
    private String mime;
    private Long uploadedBy;
    /** EVIDENCE=正式证据；ATTACHMENT=描述附件（粘贴截图等） */
    private String category;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
