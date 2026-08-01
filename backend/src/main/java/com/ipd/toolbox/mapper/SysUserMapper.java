package com.ipd.toolbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipd.toolbox.domain.entity.SysUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<String> findRoleCodes(Long userId);

    @Insert("INSERT IGNORE INTO sys_user_role(user_id, role_id) " +
            "SELECT #{userId}, id FROM sys_role")
    void assignAllRoles(Long userId);
}
