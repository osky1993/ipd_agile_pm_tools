package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.service.IterationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/iterations")
/**
 * 迭代管理控制器：提供迭代列表、增改、项目内关联需求项维护与复盘入口。
 * 与看板、工作项状态变更和效率分析形成闭环。
 */
public class IterationController {

    private final IterationService service;

    public IterationController(IterationService service) {
        this.service = service;
    }

    /**
     * 查询项目全部迭代。
     *
     * <p>用途：供迭代下拉选择、时间线导航和统计页面引用。
     *
     * @param projectId 项目 ID
     * @return 该项目的迭代列表
     * <p>副作用：只读。
     */
    @GetMapping
    public Result<List<Iteration>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /**
     * 查询单个迭代的复盘快照。
     *
     * <p>用途：展示承诺与完成对比、溢出明细、速度与交付趋势。
     *
     * <p>返回：IterationService.Retro，包含复盘统计、异常项和建议。
     * <p>副作用：仅读；适配报表面板。
     */
    @GetMapping("/{id}/retro")
    public Result<IterationService.Retro> retro(@PathVariable Long id) {
        return Result.ok(service.retro(id));
    }

    /**
     * 新建迭代。
     *
     * <p>用途：创建项目时间盒子；服务层自动补齐编码与默认状态。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>入库一条 Iteration。</li>
     *   <li>默认状态、时间边界字段按服务约束规范化。</li>
     * </ul>
     * <p>失败回滚：写入失败不应产生半成品迭代记录（事务原子）。
     */
    @PostMapping
    public Result<Iteration> create(@RequestBody Iteration it) {
        return Result.ok(service.create(it));
    }

    /**
     * 更新迭代元数据。
     *
     * <p>用途：调整目标、开始/结束日期、可见性等非结构性属性。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>更新传入字段映射列。</li>
     *   <li>不建议修改已归档历史的主键字段。</li>
     * </ul>
     *
     * <p>返回：更新后的迭代实体。
     */
    @PutMapping("/{id}")
    public Result<Iteration> update(@PathVariable Long id, @RequestBody Iteration it) {
        return Result.ok(service.update(id, it));
    }

    /**
     * 查询某迭代下的工作项清单。
     *
     * <p>用途：展示排期映射（当前投放中的需求/任务）。
     * <p>副作用：只读，返回排序由服务层定义。
     */
    @GetMapping("/{id}/items")
    public Result<List<WorkItem>> items(@PathVariable Long id) {
        return Result.ok(service.items(id));
    }

    /**
     * 将工作项绑定到迭代。
     *
     * <p>用途：人工排期校准；与工作项当前状态是否可排期相关联的校验由服务层执行。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>为指定工作项写入迭代归属。</li>
     *   <li>变更可触发排期/看板视图更新。</li>
     * </ul>
     */
    @PostMapping("/{id}/assign/{workItemId}")
    public Result<Void> assign(@PathVariable Long id, @PathVariable Long workItemId) {
        service.assign(id, workItemId);
        return Result.ok();
    }

    /**
     * 移除工作项的迭代归属。
     *
     * <p>用途：用于转移排期或临时取消承诺。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>清空 workItem 的迭代绑定。</li>
     *   <li>通常保留历史日志以便追溯排期变更。</li>
     * </ul>
     *
     * <p>失败边界：目标 workItemId 不存在或已解绑时返回错误码，不应产生副作用。
     */
    @DeleteMapping("/items/{workItemId}")
    public Result<Void> remove(@PathVariable Long workItemId) {
        service.remove(workItemId);
        return Result.ok();
    }
}
