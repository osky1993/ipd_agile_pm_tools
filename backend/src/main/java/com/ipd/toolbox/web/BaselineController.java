package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Baseline;
import com.ipd.toolbox.domain.entity.BaselineItem;
import com.ipd.toolbox.service.BaselineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/baselines")
/**
 * 基线控制器：返回基线列表、详情和基线快照对比。
 * 典型用途：
 * - 基线管理页查看承诺快照
 * - 管控面板对比“当前 vs 基线”偏差（蔓延、移除、日期偏差、估算漂移）
 */
public class BaselineController {

    private final BaselineService service;

    public BaselineController(BaselineService service) {
        this.service = service;
    }

    /**
     * 查询项目基线列表。
     *
     * 用途：
     * 获取某项目历史基线集合，供基线管理页与偏差分析入口展示。
     *
     * 入参约束：
     * projectId 为必填项目主键，不允许为 null。
     *
     * 返回：
     * 返回基线对象列表，按服务层既定排序返回，默认可按创建时间降序。
     *
     * 异常与边界：
     * 对无权限项目或不存在项目返回错误；未命中时返回空列表。
     */
    @GetMapping
    public Result<List<Baseline>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /**
     * 查询单个基线及其明细项。
     *
     * 用途：
     * 在基线详情页一次性返回基线定义与具体明细，便于“单页读取”渲染。
     *
     * 入参约束：
     * id 为存在的基线主键；service 层负责是否返回被归档基线。
     *
     * 更新粒度：
     * 本接口无更新逻辑，仅进行聚合读取。返回结构中包含 baseline 与 items 两个区域，
     * 其中 items 可能在 service 层预先装配去重/排序。
     *
     * 返回：
     * 返回 {"baseline":..., "items":[...]} 的对象视图。
     *
     * 异常与边界：
     * 基线不存在或无权限访问将返回错误；items 若为空返回空数组。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        Baseline b = service.get(id);
        List<BaselineItem> items = service.items(id);
        return Result.ok(Map.of("baseline", b, "items", items));
    }

    /**
     * 对比当前状态与基线差异。
     *
     * 用途：
     * 计算“范围蔓延/范围收回、日期偏差、估算漂移”等偏差指标。
     *
     * 入参约束：
     * id 为基线主键；基线内项目范围与快照版本决定对比对象。
     *
     * 返回：
     * 返回服务定义的 Diff 结构，包含差异项与建议提示（服务端口径为准）。
     *
     * 异常与边界：
     * 基线缺失、基线数据被清理或上下文异常时返回业务错误；空快照可能导致部分指标为 null/0。
     */
    @GetMapping("/{id}/diff")
    public Result<BaselineService.Diff> diff(@PathVariable Long id) {
        return Result.ok(service.diff(id));
    }

    /**
     * 手工建基线请求体。
     *
     * projectId 必填；name 用于定位该次快照的可读标签。
     */
    public record CreateRequest(Long projectId, String name) {
    }

    /**
     * 手动创建基线。
     *
     * 用途：
     * 当项目阶段需要补齐历史节点、修正偏差或临时对齐状态时，人工触发基线快照。
     *
     * 入参约束：
     * projectId 必填；name 建议非空以利于搜索与归档。
     *
     * 更新粒度：
     * 创建一条新基线记录，标记为 MANUAL 来源；通常会创建或覆盖基线对比依赖的快照元数据。
     * 同一项目可并存多条基线，返回对象的 id 可用于后续 diff/detail 调用。
     *
     * 返回：
     * 返回新建基线主实体（含持久化后的主键信息）。
     *
     * 异常与边界：
     * 创建过程中若项目状态不允许或输入缺失，会直接返回业务错误；服务端不提供幂等语义，重试会创建新基线（除唯一约束覆盖）。
     */
    @PostMapping
    public Result<Baseline> create(@RequestBody CreateRequest req) {
        return Result.ok(service.create(req.projectId(), req.name(), "MANUAL", null, null));
    }
}
