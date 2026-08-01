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
public class MetricsController {

    private final MetricsService metricsService;
    private final TraceMatrixService traceMatrixService;

    public MetricsController(MetricsService metricsService, TraceMatrixService traceMatrixService) {
        this.metricsService = metricsService;
        this.traceMatrixService = traceMatrixService;
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam Long projectId) {
        return Result.ok(metricsService.overview(projectId));
    }

    @GetMapping("/drilldown")
    public Result<List<WorkItem>> drilldown(@RequestParam Long projectId, @RequestParam String metric) {
        return Result.ok(metricsService.drilldown(projectId, metric));
    }

    @GetMapping("/trace-matrix")
    public Result<List<TraceMatrixService.Row>> traceMatrix(@RequestParam Long projectId) {
        return Result.ok(traceMatrixService.matrix(projectId));
    }

    /** 工作项 CSV 导出（T709）。 */
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
