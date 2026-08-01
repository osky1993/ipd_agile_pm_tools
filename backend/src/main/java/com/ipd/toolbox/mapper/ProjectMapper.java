package com.ipd.toolbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipd.toolbox.domain.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
