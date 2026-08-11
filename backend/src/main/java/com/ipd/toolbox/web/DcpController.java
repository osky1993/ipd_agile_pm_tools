package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.DcpService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/dcp")
/**
 * DCP 控制器：DCP 条件展示 + 决议记录。
 * 同时承载阶段级决策面板读取与审批动作落库两个职责。
 */
public class DcpController {

    private final DcpService service;

    public DcpController(DcpService service) {
        this.service = service;
    }

    /**
     * 获取 DCP 决策面板快照。
     *
     * 用途：
     * 读取某一 stage gate 下的条件、证据与红线状态，支持决策页面“一键材料”展示。
     *
     * 入参约束：
     * stageGateId 为目标阶段主键，不能为空。
     *
     * 返回：
     * 返回 Overview 快照模型（状态字段、红线判断、证据关联）。
     *
     * 异常与边界：
     * 查询对象不存在或无权限时返回错误；快照中部分字段可能为空，表示该阶段待补齐材料。
     */
    @GetMapping("/gates/{stageGateId}/overview")
    public Result<DcpService.Overview> overview(@PathVariable Long stageGateId) {
        return Result.ok(service.overview(stageGateId));
    }

    /** DCP 审核入参：结论、原因、关联风险与承诺截止日。 */
    public record ReviewRequest(String conclusion, String reason, Long linkedRiskId, LocalDate commitmentDue) {
    }

    /**
     * 提交 DCP 决策结论。
     *
     * 用途：
     * 根据通过/未通过/暂缓等结论推进阶段评审决策并记录追溯信息。
     *
     * 入参约束：
     * conclusion 为枚举化结论；reason 为审计说明；linkedRiskId 与 commitmentDue 根据结论可选或要求性由服务校验。
     *
     * 更新粒度：
     * 产生一条新决策事件（常见包含 prevDecisionId 追溯关系）、更新阶段 gate 评估结果上下文；
     * 仅在审计链路写入成功时才返回成功响应。
     *
     * 返回：
     * 返回落盘后的 Decision 结果。
     *
     * 异常与边界：
     * 数据缺失、并发提交冲突、状态不可审阅会拒绝本次提交；该接口不应在未刷新决策快照的条件下重复盲提交。
     */
    @PostMapping("/gates/{stageGateId}/review")
    public Result<Decision> review(@PathVariable Long stageGateId, @RequestBody ReviewRequest req) {
        return Result.ok(service.review(stageGateId, req.conclusion(), req.reason(),
                req.linkedRiskId(), req.commitmentDue()));
    }
}
