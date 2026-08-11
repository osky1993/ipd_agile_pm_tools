package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.service.GateCriterionService;
import com.ipd.toolbox.service.CriterionTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gate-criteria")
/**
 * 门禁条件控制器：管理 DCP 门禁条目与模板应用。
 * 条件状态会影响需求就绪性、红线告警与阶段放行决策，属于高影响力写链路。
 */
public class GateCriterionController {

    private final GateCriterionService service;
    private final CriterionTemplateService templateService;

    public GateCriterionController(GateCriterionService service,
                                   CriterionTemplateService templateService) {
        this.service = service;
        this.templateService = templateService;
    }

    /**
     * 获取 DCP 条件模板清单。
     *
     * 用途：
     * 提供可复用门禁模板，便于评审前快速初始化条件集。
     *
     * 入参约束：
     * 无参数。返回模板数据可能受上下文权限过滤。
     *
     * 返回：
     * 返回模板对象列表，包含条件文本、红线标识与应用指引。
     *
     * 异常与边界：
     * 模板库为空时返回空列表；若模板版本不一致，前端应提示刷新缓存。
     */
    @GetMapping("/templates")
    public Result<List<CriterionTemplateService.Template>> templates() {
        return Result.ok(templateService.templates());
    }

    /** 应用模板请求体。 */
    public record ApplyTemplateRequest(Long projectId, Long stageGateId, String templateKey) {
    }

    /**
     * 将模板应用到指定项目与阶段门禁。
     *
     * 用途：
     * 按 templateKey 一键铺设标准化门禁条件，减少评审前人工逐条配置成本。
     *
     * 入参约束：
     * projectId/stageGateId/templateKey 均为必填；templateKey 不存在会被拒绝。
     *
     * 更新粒度：
     * 批量创建门禁条件项，部分重复条件按服务策略跳过或覆盖；执行结束后返回应用结果统计。
     * 该动作可能改变多条门禁项，属于可观测的批量写入。
     *
     * 返回：
     * 返回应用过程结构体（通常含新增数/跳过数/失败项等）。
     *
     * 异常与边界：
     * 并发重复应用时以服务端幂等策略为准，建议按幂等键串行提交。
     */
    @PostMapping("/apply-template")
    public Result<Map<String, Object>> applyTemplate(@RequestBody ApplyTemplateRequest req) {
        return Result.ok(templateService.apply(req.projectId(), req.stageGateId(), req.templateKey()));
    }

    /**
     * 按项目与阶段查询门禁条件。
     *
     * 用途：
     * 返回某项目在指定阶段的条件明细，支撑就绪性判断与红线核查。
     *
     * 入参约束：
     * projectId 为必填；stageGateId 与 isReadiness 可选。
     *
     * 返回：
     * 返回门禁条件列表，字段为空时表示该阶段无定义条件。
     *
     * 异常与边界：
     * 项目无权限返回错误；列表展示顺序由服务层约定。
     */
    @GetMapping
    public Result<List<GateCriterion>> list(@RequestParam Long projectId,
                                            @RequestParam(required = false) Long stageGateId,
                                            @RequestParam(required = false) Integer isReadiness) {
        return Result.ok(service.list(projectId, stageGateId, isReadiness));
    }

    /**
     * 新增单条门禁条件。
     *
     * 用途：
     * 补充阶段 gate 的校验项，定义证据要求、是否红线等规则元信息。
     *
     * 入参约束：
     * 需提供完整条件描述与所属上下文字段。
     *
     * 更新粒度：
     * 写入一条 GateCriterion 主记录；返回对象包含服务端补全的元数据（如创建人、创建时间、版本号）。
     *
     * 返回：
     * 返回持久化后的 GateCriterion 对象。
     *
     * 异常与边界：
     * 缺字段、重复条件或阶段不可写状态会被服务层拦截。
     */
    @PostMapping
    public Result<GateCriterion> create(@RequestBody GateCriterion c) {
        return Result.ok(service.create(c));
    }

    /**
     * 更新单条门禁条件。
     *
     * 用途：
     * 对已有门禁条件进行热更新，例如结论、证据要求、红线标识变更。
     *
     * 入参约束：
     * id 为已存在的门禁条件主键；请求体提供待更新字段。
     *
     * 更新粒度：
     * 按 id 定位记录并执行字段级更新；若无实质变化可由服务层做空更新保护。
     * 该接口会直接影响当前阶段放行结果的判定分支。
     *
     * 返回：
     * 返回更新后的 GateCriterion 对象。
     *
     * 异常与边界：
     * 条件不存在或处于锁定态时返回失败；前端禁止并发覆盖同一记录。
     */
    @PutMapping("/{id}")
    public Result<GateCriterion> update(@PathVariable Long id, @RequestBody GateCriterion c) {
        return Result.ok(service.update(id, c));
    }

    /**
     * 删除门禁条件。
     *
     * 用途：
     * 清理不再适用或重复冗余的条件项，支持模板重构和历史演进。
     *
     * 入参约束：
     * id 为待删除的记录主键。
     *
     * 更新粒度：
     * 删除单条条件（逻辑删除或物理删除由服务决定），影响当前阶段的后续放行校验。
     * 调用方不应依赖删除结果中包含已删除子实体的状态。
     *
     * 返回：
     * 成功返回空成功体；失败返回错误码与信息。
     *
     * 异常与边界：
     * 删除关键门禁条件可能触发后续决策校验异常，调用方应在操作前做好确认。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
