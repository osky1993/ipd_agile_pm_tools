package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.DecisionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/decisions")
/**
 * 决策查询与记录入口。
 * 决策对象按事件化模型保存，支持追溯与审计分析。
 */
public class DecisionController {

    private final DecisionService service;

    public DecisionController(DecisionService service) {
        this.service = service;
    }

    /**
     * 查询项目内决策列表。
     *
     * 用途：
     * 为决策台账与页面审计提供按项目聚合的决策记录。
     *
     * 入参约束：
     * projectId 为项目主键，不能为空。
     *
     * 返回：
     * 返回该项目下所有决策对象集合（服务层决定是否分页与排序）。
     *
     * 异常与边界：
     * 无效 projectId 或无权限时返回业务异常；返回列表顺序与分页口径按服务实现。
     */
    @GetMapping
    public Result<List<Decision>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /**
     * 记录一条决策事件。
     *
     * 用途：
     * 接受上游页面或任务链路上传的决策明细并持久化。
     *
     * 入参约束：
     * Decision 对象需包含可被服务端识别的必要字段（如类型、主体、决策结果、项目上下文）。
     *
     * 更新粒度：
     * 写入一条决策审计事件，不允许覆盖既有决策对象（以服务端主键为准）；
     * 通常伴随创建时间、操作者、上下文元数据。
     *
     * 返回：
     * 返回持久化后的 Decision 对象。
     *
     * 异常与边界：
     * 非法字段、重复提交或业务规则校验失败会拒绝提交；建议调用方避免在短时间内重复发起同一事件。
     */
    @PostMapping
    public Result<Decision> record(@RequestBody Decision d) {
        return Result.ok(service.record(d));
    }
}
