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
public class PerfController {

    private final PerfService perfService;
    private final ImprovementService improvementService;

    public PerfController(PerfService perfService, ImprovementService improvementService) {
        this.perfService = perfService;
        this.improvementService = improvementService;
    }

    @GetMapping("/metrics")
    public Result<PerfService.PerfOverview> metrics(@RequestParam Long projectId) {
        return Result.ok(perfService.metrics(projectId));
    }

    /** 全指标趋势序列（每日快照）。 */
    @GetMapping("/trends")
    public Result<java.util.Map<String, List<PerfService.TrendPoint>>> trends(
            @RequestParam Long projectId, @RequestParam(defaultValue = "60") int days) {
        return Result.ok(perfService.trends(projectId, days));
    }

    /** 累积流图：每天各状态存量（状态时间线回放）。 */
    @GetMapping("/cfd")
    public Result<List<PerfService.CfdPoint>> cfd(@RequestParam Long projectId,
                                                  @RequestParam(defaultValue = "56") int days) {
        return Result.ok(perfService.cfd(projectId, days));
    }

    public record TargetReq(Long projectId, String metricKey, Double targetValue) {
    }

    /** 设定/清除（targetValue=null）指标目标。 */
    @PutMapping("/target")
    public Result<PerfService.Metric> setTarget(@RequestBody TargetReq req) {
        return Result.ok(perfService.setTarget(req.projectId(), req.metricKey(), req.targetValue()));
    }

    @GetMapping("/improvements")
    public Result<List<Improvement>> improvements(@RequestParam Long projectId,
                                                  @RequestParam(required = false) String status) {
        return Result.ok(improvementService.list(projectId, status));
    }

    @PostMapping("/improvements")
    public Result<Improvement> createImprovement(@RequestBody Improvement in) {
        return Result.ok(improvementService.create(in));
    }

    @PutMapping("/improvements/{id}")
    public Result<Improvement> updateImprovement(@PathVariable Long id, @RequestBody Improvement patch) {
        return Result.ok(improvementService.update(id, patch));
    }

    public record TransitionReq(String toStatus, BigDecimal resultValue, String conclusion) {
    }

    @PostMapping("/improvements/{id}/transition")
    public Result<Improvement> transition(@PathVariable Long id, @RequestBody TransitionReq req) {
        return Result.ok(improvementService.transition(id, req.toStatus(), req.resultValue(), req.conclusion()));
    }

    @DeleteMapping("/improvements/{id}")
    public Result<Void> deleteImprovement(@PathVariable Long id) {
        improvementService.delete(id);
        return Result.ok();
    }
}
