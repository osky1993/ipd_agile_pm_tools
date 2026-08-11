package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.service.TraceLinkService;
import com.ipd.toolbox.statemachine.StateMachine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/** 前端下拉/表单所需的元数据：工作项类型、追溯关系、各类型状态集合、用户清单。 */
@RestController
@RequestMapping("/api/meta")
/**
 * 元数据控制器：提供前端静态下拉与字段约束的动态字典。
 * 包括用户、工作项类型、追溯关系、状态枚举集合。
 */
public class MetaController {

    private final com.ipd.toolbox.mapper.SysUserMapper userMapper;

    public MetaController(com.ipd.toolbox.mapper.SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 启用中的用户清单（责任人下拉/显示名映射用）。 */
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (com.ipd.toolbox.domain.entity.SysUser u : userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ipd.toolbox.domain.entity.SysUser>()
                        .eq("enabled", 1).orderByAsc("id"))) {
            list.add(Map.of("id", u.getId(), "username", u.getUsername(),
                    "displayName", u.getDisplayName() == null ? u.getUsername() : u.getDisplayName()));
        }
        return Result.ok(list);
    }

    /** 获取工作项类型枚举（值、缩写、展示名）。 */
    @GetMapping("/work-item-types")
    public Result<List<Map<String, String>>> workItemTypes() {
        List<Map<String, String>> list = new ArrayList<>();
        for (WorkItemType t : WorkItemType.values()) {
            list.add(Map.of("value", t.name(), "abbr", t.abbr(), "label", t.label()));
        }
        return Result.ok(list);
    }

    /** 获取追溯关系枚举（例如 affects、implements、verifies）。 */
    @GetMapping("/trace-relations")
    public Result<Set<String>> traceRelations() {
        return Result.ok(TraceLinkService.RELATIONS);
    }

    /** 按工作项类型返回可用状态集合，用于动态表单和状态下拉。 */
    @GetMapping("/statuses")
    public Result<Map<String, Set<String>>> statuses() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (WorkItemType t : WorkItemType.values()) {
            map.put(t.name(), StateMachine.allStatuses(t));
        }
        return Result.ok(map);
    }
}
