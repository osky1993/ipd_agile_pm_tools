package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("baseline")
public class Baseline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    /** DCP=评审通过自动固化；MANUAL=手动建立 */
    private String source;
    private Long stageGateId;
    private Long decisionId;
    private Integer itemCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
