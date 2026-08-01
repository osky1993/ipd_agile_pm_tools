package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.service.TraceLinkService;
import com.ipd.toolbox.statemachine.StateMachine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/** 前端下拉/表单所需的元数据：工作项类型、追溯关系、各类型状态集合。 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @GetMapping("/work-item-types")
    public Result<List<Map<String, String>>> workItemTypes() {
        List<Map<String, String>> list = new ArrayList<>();
        for (WorkItemType t : WorkItemType.values()) {
            list.add(Map.of("value", t.name(), "abbr", t.abbr(), "label", t.label()));
        }
        return Result.ok(list);
    }

    @GetMapping("/trace-relations")
    public Result<Set<String>> traceRelations() {
        return Result.ok(TraceLinkService.RELATIONS);
    }

    @GetMapping("/statuses")
    public Result<Map<String, Set<String>>> statuses() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (WorkItemType t : WorkItemType.values()) {
            map.put(t.name(), StateMachine.allStatuses(t));
        }
        return Result.ok(map);
    }
}
