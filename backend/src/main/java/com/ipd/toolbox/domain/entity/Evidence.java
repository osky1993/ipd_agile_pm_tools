package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

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
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
