package com.ipd.toolbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipd.toolbox.domain.entity.WorkItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkItemMapper extends BaseMapper<WorkItem> {

    /**
     * 时光机专用：绕过 @TableLogic 取全量（含已逻辑删除的项）——
     * 历史上存在、后来删除的工作项也应出现在时点回放中，否则历史失真。
     */
    @Select("SELECT * FROM work_item WHERE project_id = #{projectId}")
    List<WorkItem> selectAllIncludingDeleted(@Param("projectId") Long projectId);
}
