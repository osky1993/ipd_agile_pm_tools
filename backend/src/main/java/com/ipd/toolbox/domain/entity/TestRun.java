package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试执行记录实体。
 *
 * <p>记录某条用例在某版本/时间下的执行结果与实际输出，
 * 支撑缺陷关联与发布质量验证的闭环证据。
 */
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
