package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Improvement;
import com.ipd.toolbox.service.ImprovementService;
import com.ipd.toolbox.service.PerfService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/perf")
/** 
 * 性能与效率控制器：按项目返回关键指标概览、趋势、流量可视化数据和改善项。
 * 上层 Dashboard、阶段回顾、效能看板依赖这组接口计算健康信号。
 */
public class PerfController {

    private final PerfService perfService;
    private final ImprovementService improvementService;

    public PerfController(PerfService perfService, ImprovementService improvementService) {
        this.perfService = perfService;
        this.improvementService = improvementService;
    }

    /**
     * 获取项目级性能看板。
     *
     * <p>用途：返回指标分组（吞吐/滞留/目标）给项目健康页的汇总卡片，服务于 Dashboard 首屏。
     *
     * <p>入参：projectId 必填，缺省无其他过滤参数。
     * <p>输出：PerfService.PerfOverview，未查询到项目时仍返回空结构并由统一响应层处理 code。
     *
     * <p>更新副作用：无（只读）。异常仅限参数校验与服务层运行时异常。
     */
    @GetMapping("/metrics")
    public Result<PerfService.PerfOverview> metrics(@RequestParam Long projectId) {
        return Result.ok(perfService.metrics(projectId));
    }

    /**
     * 按天查询全指标趋势序列。
     *
     * <p>用途：供趋势图和滚动健康评估使用。days 控制回看窗口，过小返回对应窗口历史快照。
     *
     * <p>参数约束：
     * <ul>
     *   <li>projectId 必填。</li>
     *   <li>days 默认 60，建议服务层对异常大值做上限保护（避免一次性大范围扫描）。</li>
     * </ul>
     *
     * <p>返回：按日期升序趋势点列表；时间窗边界由服务层约定。
     * <p>副作用：无持久化，仅读取快照序列。
     */
    @GetMapping("/trends")
    public Result<java.util.Map<String, List<PerfService.TrendPoint>>> trends(
            @RequestParam Long projectId, @RequestParam(defaultValue = "60") int days) {
        return Result.ok(perfService.trends(projectId, days));
    }

    /**
     * 查询累计流程图（CDF）序列。
     *
     * <p>用途：展示每日各状态 WIP/流量分布，支持状态时间线回放。
     *
     * <p>参数约束：
     * <ul>
     *   <li>projectId 必填。</li>
     *   <li>days 默认 56，通常用于 8 周窗口分析。</li>
     * </ul>
     *
     * <p>返回：每日日志点集合，前端按状态名聚合渲染。
     * <p>副作用：无。
     */
    @GetMapping("/cfd")
    public Result<List<PerfService.CfdPoint>> cfd(@RequestParam Long projectId,
                                                  @RequestParam(defaultValue = "56") int days) {
        return Result.ok(perfService.cfd(projectId, days));
    }

    public record TargetReq(Long projectId, String metricKey, Double targetValue) {
    }

    /**
     * 写入或清除单指标目标值。
     *
     * <p>用途：为 dashboard 和周报提供“目标偏离”判定阈值。
     *
     * <p>更新粒度说明：
     * <ul>
     *   <li>targetValue 非空：新建/更新 project+metricKey 的目标值。</li>
     *   <li>targetValue 为 null：按业务约定清除目标配置并回退系统默认基线。</li>
     *   <li>更新成功返回最新目标记录，失败需返回统一错误码给前端展示。</li>
     * </ul>
     *
     * <p>失败/边界：
     * <ul>
     *   <li>projectId/metricKey 缺失由参数校验拦截。</li>
     *   <li>metricKey 不存在于系统口径时应由服务层拒绝。</li>
     * </ul>
     */
    @PutMapping("/target")
    public Result<PerfService.Metric> setTarget(@RequestBody TargetReq req) {
        return Result.ok(perfService.setTarget(req.projectId(), req.metricKey(), req.targetValue()));
    }

    /**
     * 查询持续改进列表（可按状态过滤）。
     *
     * <p>用途：支持 PM 在项目维度管理 DOING/PLANNING/VERIFIED 等改进行为。
     * <p>参数：status 可空，空时返回全部状态。
     * <p>返回：改进项数组；默认按服务层约定排序，通常是更新时间倒序。
     * <p>副作用：无数据库写入。
     */
    @GetMapping("/improvements")
    public Result<List<Improvement>> improvements(@RequestParam Long projectId,
                                                 @RequestParam(required = false) String status) {
        return Result.ok(improvementService.list(projectId, status));
    }

    /**
     * 新建持续改进项。
     *
     * <p>用途：为问题关闭/回归场景下形成可追踪改进动作。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>写入一条 Improvement 记录。</li>
     *   <li>服务层负责字段清洗、默认值和归档字段补齐。</li>
     *   <li>不允许提交时仅返回错误码并中断本次保存。</li>
     * </ul>
     *
     * <p>副作用：持久化一条改进项；若创建失败不产生部分写入（由事务保证）。
     */
    @PostMapping("/improvements")
    public Result<Improvement> createImprovement(@RequestBody Improvement in) {
        return Result.ok(improvementService.create(in));
    }

    /**
     * 更新持续改进项元数据。
     *
     * <p>用途：修订标题、度量口径、目标值、说明等业务字段。
     *
     * <p>失败场景：
     * <ul>
     *   <li>目标记录不存在 -> 统一异常路径。</li>
     *   <li>payload 字段非法 -> 校验层返回错误。</li>
     * </ul>
     *
     * <p>更新粒度：仅改写传入字段对应列，其余字段保持原值，返回更新后实体。
     */
    @PutMapping("/improvements/{id}")
    public Result<Improvement> updateImprovement(@PathVariable Long id, @RequestBody Improvement patch) {
        return Result.ok(improvementService.update(id, patch));
    }

    public record TransitionReq(String toStatus, BigDecimal resultValue, String conclusion) {
    }

    /**
     * 推进持续改进行为状态机。
     *
     * <p>用途：驱动 DOING/VERIFIED/CLOSED 等动作，同时可附加测量结果与结论说明。
     *
     * <p>更新副作用：
     * <ul>
     *   <li>更新改进项状态、时间戳及结论类文本。</li>
     *   <li>可产生与项目指标的再次评估输入。</li>
     * </ul>
     *
     * <p>边界：
     * <ul>
     *   <li>toStatus 必须是服务层定义的合法迁移。</li>
     *   <li>非法转移会阻断并返回错误码，不做回滚外副作用。</li>
     * </ul>
     */
    @PostMapping("/improvements/{id}/transition")
    public Result<Improvement> transition(@PathVariable Long id, @RequestBody TransitionReq req) {
        return Result.ok(improvementService.transition(id, req.toStatus(), req.resultValue(), req.conclusion()));
    }

    /**
     * 删除持续改进项。
     *
     * <p>用途：处理重复创建/作废场景。删除前置一般由调用方确认确认码或列表复核完成。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>物理移除目标记录。</li>
     *   <li>相关历史追踪可通过审计链路保留（取决于服务层实现）。</li>
     * </ul>
     *
     * <p>注意：删除成功返回空 Data（Result<Void>），不表示清理了关联指标缓存。
     */
    @DeleteMapping("/improvements/{id}")
    public Result<Void> deleteImprovement(@PathVariable Long id) {
        improvementService.delete(id);
        return Result.ok();
    }
}
