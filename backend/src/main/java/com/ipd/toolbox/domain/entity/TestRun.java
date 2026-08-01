package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("test_run")
public class TestRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long projectId;
    private Long testCaseId;
    private String result;
    private String actual;
    private Long runVersionId;
    private Long runBy;
    private LocalDateTime runAt;
    private Long defectId;
}
