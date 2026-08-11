package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Lesson;
import com.ipd.toolbox.service.LessonService;
import com.ipd.toolbox.service.RiskPatternService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 组织资产：经验教训库 + 跨项目风险模式库。 */
@RestController
@RequestMapping("/api/assets")
/**
 * 组织资产控制器：统一承载经验教训与风险模式库相关接口。
 * 这两个数据源均用于复盘、复用和处置策略评估，帮助团队沉淀可复用的治理知识。
 */
public class AssetsController {

    private final LessonService lessonService;
    private final RiskPatternService riskPatternService;

    public AssetsController(LessonService lessonService, RiskPatternService riskPatternService) {
        this.lessonService = lessonService;
        this.riskPatternService = riskPatternService;
    }

    /**
     * 搜索或过滤经验教训条目。
     *
     * 用途：
     * 提供经验教训知识库读接口，支持通过关键字、分类、项目三类维度做复盘检索。
     *
     * 入参约束：
     * keyword/category/projectId 均为可选过滤条件，三者可同时为空表示全量查询。
     *
     * 返回：
     * 返回匹配条件的 Lesson 列表；无结果返回空列表，便于前端直接渲染“无匹配”态。
     *
     * 异常与边界：
     * 查询范围越大时可能产生慢查询压力；分页与权限过滤策略由服务层统一兜底。
     */
    @GetMapping("/lessons")
    public Result<List<Lesson>> lessons(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) Long projectId) {
        return Result.ok(lessonService.search(keyword, category, projectId));
    }

    /**
     * 新增经验教训条目。
     *
     * 用途：
     * 将复盘中形成的经验记录持久化，供后续项目查询与风险处置复用。
     *
     * 入参约束：
     * lesson 必须包含可序列化并可校验的核心字段；缺失关键字段将由服务层进行校验并返回失败。
     *
     * 更新粒度：
     * 写入一条 lesson 记录，通常伴随创建时间、创建人、项目归属等基础元数据。
     * 该接口不做幂等保护，重试会产生重复条目（以服务端唯一约束为准）。
     *
     * 返回：
     * 返回落库后的 Lesson 对象（含主键与服务端填充字段）。
     *
     * 异常与边界：
     * 创建失败时抛出业务异常；如需严格去重，请在调用方层面控制重复提交。
     */
    @PostMapping("/lessons")
    public Result<Lesson> createLesson(@RequestBody Lesson lesson) {
        return Result.ok(lessonService.create(lesson));
    }

    /**
     * 删除经验教训条目。
     *
     * 用途：
     * 清理误录入或过期失效的复盘知识条目，减少知识库噪音。
     *
     * 入参约束：
     * id 必须为存在且可访问的知识条目主键。
     *
     * 更新粒度：
     * 触发单条记录的删除流程。删除方式（逻辑/物理）与关联关系处理由服务层决定，
     * 调用方不应依赖返回值中的级联结构。
     *
     * 返回：
     * 成功则返回空成功体；失败则按服务层异常返回错误。
     *
     * 异常与边界：
     * 引用该记录的历史页面或外部文档可能出现展示空缺，这是正常边界。
     */
    @DeleteMapping("/lessons/{id}")
    public Result<Void> deleteLesson(@PathVariable Long id) {
        lessonService.delete(id);
        return Result.ok();
    }

    /**
     * 查询风险模式库聚合视图。
     *
     * 用途：
     * 提供跨项目风险模式列表，支持风险库命中率提升与经验复用决策。
     *
     * 入参约束：
     * keyword 可选，用于文本模糊匹配；空值表示返回默认聚合结果。
     *
     * 返回：
     * 返回风险模式聚合对象，常见包括结局分布、处置时长和高频标签信息。
     *
     * 异常与边界：
     * 结果依赖历史累计数据，数据缺口可能导致部分统计维度为空或为 0，需按前端提示兜底。
     */
    @GetMapping("/risk-patterns")
    public Result<RiskPatternService.Patterns> riskPatterns(
            @RequestParam(required = false) String keyword) {
        return Result.ok(riskPatternService.patterns(keyword));
    }
}
