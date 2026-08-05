package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lesson")
public class Lesson {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    /** WELL(做得好)/IMPROVE(待改进)/PROCESS/TECH/SUPPLY/OTHER */
    private String category;
    private String title;
    private String detail;
    /** 可选来源：WORK_ITEM/DECISION/ITERATION */
    private String sourceType;
    private Long sourceId;
    private Long createdBy;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
