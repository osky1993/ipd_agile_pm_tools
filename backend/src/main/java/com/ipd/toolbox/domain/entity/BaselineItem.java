package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 基线明细：建立时点的冻结值，此后不随工作项变化。 */
@Data
@TableName("baseline_item")
public class BaselineItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long baselineId;
    private Long workItemId;
    private String code;
    private String title;
    private String type;
    private String status;
    private String estimate;
    private LocalDate plannedDate;
}
