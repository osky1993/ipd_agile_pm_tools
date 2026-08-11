package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.ChangeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/changes")
/**
 * 变更控制器：按变更 ID 触发影响分析、提交变更决策。
 * 决策结果会落盘为 Decision，并可触发相关风险/项目链路。
 */
public class ChangeController {

    private final ChangeService service;

    public ChangeController(ChangeService service) {
        this.service = service;
    }

    /**
     * 生成变更影响分析结果。
     *
     * 用途：
     * 按变更 ID 拉起影响链路计算，返回该变更对关联项目、风险和验收项的影响建议。
     *
     * 入参约束：
     * id 必须是可访问的变更主键。
     *
     * 更新粒度：
     * 分析动作通常触发服务层对关联实体的推演、缓存或快照写入（以具体实现为准）；
     * 接口不承诺天然幂等，失败重试可能重复产生中间态记录。
     *
     * 返回：
     * 返回 ImpactResult 结构体，供后续决策页读取并展示。
     *
     * 异常与边界：
     * 变更状态不在可分析区间、依赖关系丢失或无权限访问时返回错误。
     */
    @PostMapping("/{id}/analyze")
    public Result<ChangeService.ImpactResult> analyze(@PathVariable Long id) {
        return Result.ok(service.analyze(id));
    }

    /** 决策落地请求体。 */
    public record DecideRequest(boolean approve, String reason) {
    }

    /**
     * 落实变更决策。
     *
     * 用途：
     * 基于分析结果完成批准/否决，并将决策事实落盘。
     *
     * 入参约束：
     * approve 决定决策方向；reason 用于审计说明，建议给出明确原因。
     *
     * 更新粒度：
     * 写入 Decision 记录，并将变更状态推进为批准或否决分支；
     * 当调用失败时应保持变更状态幂等回滚到上游状态（由服务层保证）。
     *
     * 返回：
     * 返回落盘后的 Decision 对象。
     *
     * 异常与边界：
     * 重复提交、无权限、状态冲突会被拒绝，不应依赖最终一致性重试掩盖并发冲突。
     */
    @PostMapping("/{id}/decide")
    public Result<Decision> decide(@PathVariable Long id, @RequestBody DecideRequest req) {
        return Result.ok(service.decide(id, req.approve(), req.reason()));
    }
}
