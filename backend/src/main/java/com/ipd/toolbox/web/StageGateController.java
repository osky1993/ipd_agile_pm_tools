package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.service.StageGateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stage-gates")
/**
 * 阶段门控制器：维护需求管理中的阶段节点清单。
 * 阶段门与条件清单联动，用于 DCP 评审时的时间线展示。
 */
public class StageGateController {

    private final StageGateService service;

    public StageGateController(StageGateService service) {
        this.service = service;
    }

    /**
     * 列出项目阶段门清单。
     *
     * <p>用途：为前端渲染阶段门时间线与里程碑节点。
     *
     * @param projectId 项目 ID
     * @return 阶段门列表，通常按序号/日期排序
     * <p>副作用：只读。
     */
    @GetMapping
    public Result<List<StageGate>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /**
     * 新建阶段门。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>插入一条阶段门记录（名称、开始/结束、顺序）。</li>
     *   <li>后续可与条件清单、DCP 决策页联动。</li>
     * </ul>
     * <p>失败边界：重复命名或顺序冲突按服务规则拒绝。
     */
    @PostMapping
    public Result<StageGate> create(@RequestBody StageGate g) {
        return Result.ok(service.create(g));
    }

    /**
     * 更新阶段门定义。
     *
     * <p>用途：调整节点名称、关键时间或顺序；不直接替换项目里程碑历史。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>写入字段差异对应列。</li>
     *   <li>可能影响阶段门排序展示与上下游条件页显示。</li>
     * </ul>
     *
     * <p>副作用：持久化更新；建议前端提交前做幂等比对避免重复提交。
     */
    @PutMapping("/{id}")
    public Result<StageGate> update(@PathVariable Long id, @RequestBody StageGate g) {
        return Result.ok(service.update(id, g));
    }
}
