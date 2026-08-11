package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.service.RiskService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risks")
/**
 * 风险处置控制器：提供风险工作项后的转化动作。
 * 典型调用链：风险识别 -> mitigation-task -> 生成 TASK + 追溯关联。
 */
public class RiskController {

    private final RiskService service;

    public RiskController(RiskService service) {
        this.service = service;
    }

    /**
     * 风险转任务化。
     *
     * <p>用途：为风险项的处置措施生成应对任务 WORK_ITEM(TASK)，并建立 TASK → RISK 的追溯关系。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>创建一个应对任务记录（含处置动作/计划/负责人）。</li>
     *   <li>建立关系链便于后续状态联动审计。</li>
     *   <li>通常用于风险未关闭或需并行跟踪的场景。</li>
     * </ul>
     *
     * <p>失败边界：缺少可生成任务的前置条件时抛错，不修改风险主状态。
     * <p>返回：生成的 WorkItem（TASK）实例。
     */
    @PostMapping("/{id}/mitigation-task")
    public Result<WorkItem> mitigationTask(@PathVariable Long id) {
        return Result.ok(service.createMitigationTask(id));
    }
}
