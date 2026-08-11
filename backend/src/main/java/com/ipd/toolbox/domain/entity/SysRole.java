package com.ipd.toolbox.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统角色实体。
 *
 * <p>定义可用的权限角色编码与展示名称。
 * 角色系统通过用户-角色绑定提供接口级与服务级访问控制。
 */
@Data
@TableName("sys_role")
public class SysRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
}
