package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.service.MetricsService;
import com.ipd.toolbox.service.TraceMatrixService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
/**
 * 指标与追溯矩阵控制器：给 PM 看板提供统一指标口径。
 * 典型依赖：overview（顶部指标）、drilldown（问题溯源）、trace-matrix（需求-测试映射）。
 */
public class MetricsController {

    private final MetricsService metricsService;
    private final TraceMatrixService traceMatrixService;

    public MetricsController(MetricsService metricsService, TraceMatrixService traceMatrixService) {
        this.metricsService = metricsService;
        this.traceMatrixService = traceMatrixService;
    }

    /**
     * 查询项目健康摘要。
     *
     * <p>用途：返回多口径聚合指标（能力、交付、质量、成熟度），作为仪表盘顶部指标卡和预警触发基础。</p>
     *
     * @param projectId 项目 ID
     * @return 指标摘要对象（Map 结构）；空值通过服务层约定处理。
     * <p>副作用：只读。
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam Long projectId) {
        return Result.ok(metricsService.overview(projectId));
    }

    /**
     * 以某指标口径钻取工作项列表。
     *
     * <p>用途：在指标卡点击后返回明细，定位问题项（如待处理缺陷、超期事项）。</p>
     *
     * <p>更新粒度：无写入，仅返回明细集合。
     * <p>边界：metric 非法时返回参数错误；projectId 缺失则返回参数校验错误。
     */
    @GetMapping("/drilldown")
    public Result<List<WorkItem>> drilldown(@RequestParam Long projectId, @RequestParam String metric) {
        return Result.ok(metricsService.drilldown(projectId, metric));
    }

    /**
     * 查询项目趋势序列。
     *
     * <p>用途：返回缺陷流入/关闭、满足条件项、需求验收等每日快照，用于趋势图与环比分析。</p>
     *
     * <p>参数边界：
     * <ul>
     *   <li>days 控制回溯窗口，当前实现对上限有防御（如 365 天）。</li>
     * </ul>
     *
     * <p>返回：TrendPoint 列表，默认按时间正序。</p>
     */
    @GetMapping("/trend")
    public Result<List<MetricsService.TrendPoint>> trend(@RequestParam Long projectId,
                                                         @RequestParam(defaultValue = "30") int days) {
        return Result.ok(metricsService.trend(projectId, Math.min(days, 365)));
    }

    /**
     * 查询追溯矩阵。
     *
     * <p>用途：返回需求-测试映射明细，用于完整性检查与覆盖率审计。</p>
     *
     * <p>副作用：只读；用于风控指标与导出场景输入。</p>
     */
    @GetMapping("/trace-matrix")
    public Result<List<TraceMatrixService.Row>> traceMatrix(@RequestParam Long projectId) {
        return Result.ok(traceMatrixService.matrix(projectId));
    }

    /**
     * 导出工作项 CSV（当前筛选口径）。
     *
     * <p>用途：供 BI/离线复核使用，不通过 Service 透传，直接在控制器做 BOM 与转义处理。</p>
     *
     * <p>更新副作用与边界：
     * <ul>
     *   <li>仅导出内容，不改数据库。</li>
     *   <li>字段按 RFC4180 简化规则转义；换行/逗号/引号会被双引号包裹。</li>
     *   <li>响应头使用 text/csv 且 UTF-8 BOM，减少中文乱码。</li>
     * </ul>
     */
    @GetMapping("/export/work-items.csv")
    public ResponseEntity<byte[]> exportWorkItems(@RequestParam Long projectId) {
        List<WorkItem> items = metricsService.drilldownAll(projectId);
        StringBuilder sb = new StringBuilder("﻿"); // BOM，Excel 识别 UTF-8
        sb.append("编号,类型,标题,状态,责任人,优先级,创建时间\n");
        for (WorkItem w : items) {
            sb.append(csv(w.getCode())).append(',')
                    .append(csv(w.getType())).append(',')
                    .append(csv(w.getTitle())).append(',')
                    .append(csv(w.getStatus())).append(',')
                    .append(w.getOwnerId() == null ? "" : w.getOwnerId()).append(',')
                    .append(csv(w.getPriority())).append(',')
                    .append(w.getCreatedAt() == null ? "" : w.getCreatedAt()).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=work-items.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /**
     * CSV 字段防注入转义。
     *
     * <p>用途：包装逗号/引号/换行，避免 Excel 解析异常与列位移。</p>
     *
     * @param s 原始字段值
     * @return 转义后字符串
     */
    private String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
