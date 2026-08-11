package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.TimeMachineService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/timemachine")
/**
 * 时序回放控制器：用于回看历史状态快照与时点对比。
 * 支持时序分析、审计追溯和版本化汇报。
 */
public class TimeMachineController {

    private final TimeMachineService service;

    public TimeMachineController(TimeMachineService service) {
        this.service = service;
    }

    /**
     * 查询项目时序事件轨道。
     *
     * <p>用途：返回跨决策、基线、迭代、状态变更的时间线快照，用于回放与说明型复盘。</p>
     *
     * @param projectId 项目 ID
     * @return Timeline 事件序列（按时间升序）。
     * <p>副作用：只读。
     */
    @GetMapping("/timeline")
    public Result<TimeMachineService.Timeline> timeline(@RequestParam Long projectId) {
        return Result.ok(service.timeline(projectId));
    }

    /**
     * 对比两个时点（A/B）期间指标与事件变化。
     *
     * <p>用途：支持里程碑回看、版本回归验证。</p>
     *
     * <p>参数说明：
     * <ul>
     *   <li>from、to 为日期范围（含边界）；服务层应校验 from <= to。</li>
     *   <li>返回中包含新增/完成/推进指标变化与期间大事。</li>
     * </ul>
     *
     * <p>副作用：只读。
     */
    @GetMapping("/compare")
    public Result<TimeMachineService.Compare> compare(
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(service.compare(projectId, from, to));
    }

    /**
     * 重建某日收盘时的项目状态。
     *
     * <p>用途：固定日期视角下查看项目状态快照，支持“回看时点”审计。</p>
     *
     * <p>副作用：只读；不发生时间回滚，仅生成当前请求上下文中的快照视图。
     */
    @GetMapping("/as-of")
    public Result<TimeMachineService.AsOf> asOf(
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(service.asOf(projectId, date));
    }
}
