package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.service.ReadinessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/readiness")
/**
 * 准备度控制器：五大领域准备度口径与指标汇总。
 * 与 DCP 决策页共享同一套条件口径。
 */
public class ReadinessController {

    private final ReadinessService service;

    public ReadinessController(ReadinessService service) {
        this.service = service;
    }

    /**
     * 查询准备度领域枚举值。
     *
     * <p>用途：前端下拉选择域（技术/质量/供应/制造/上市）并驱动条件过滤。</p>
     */
    @GetMapping("/domains")
    public Result<List<String>> domains() {
        return Result.ok(ReadinessService.DOMAINS);
    }

    /**
     * 查询项目准备度条件清单。
     *
     * <p>用途：按项目和可选 domain 返回检查项列表，用于准备度评估页面。</p>
     *
     * @param projectId 项目 ID
     * @param domain 准备域（可选）
     * @return GateCriterion 数组
     * <p>副作用：只读；domain 缺省表示全域返回。
     */
    @GetMapping("/items")
    public Result<List<GateCriterion>> items(@RequestParam Long projectId,
                                             @RequestParam(required = false) String domain) {
        return Result.ok(service.items(projectId, domain));
    }

    /**
     * 查询准备度聚合摘要。
     *
     * <p>用途：返回是否可推进所需的汇总指标（完成率、阻塞项、风险项）。</p>
     * <p>返回：ReadinessService.Summary，用于项目总览卡片/决策入口。</p>
     * <p>副作用：只读。</p>
     */
    @GetMapping("/summary")
    public Result<ReadinessService.Summary> summary(@RequestParam Long projectId) {
        return Result.ok(service.summary(projectId));
    }
}
