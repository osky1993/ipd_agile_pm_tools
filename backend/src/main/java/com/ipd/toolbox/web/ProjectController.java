package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.service.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;
    private final com.ipd.toolbox.service.WeeklyReportService weeklyReportService;
    private final com.ipd.toolbox.service.ClosureService closureService;

    public ProjectController(ProjectService service,
                             com.ipd.toolbox.service.WeeklyReportService weeklyReportService,
                             com.ipd.toolbox.service.ClosureService closureService) {
        this.service = service;
        this.weeklyReportService = weeklyReportService;
        this.closureService = closureService;
    }

    /** 结项检查：未闭合风险/未评审 DCP/未关缺陷/在途变更/红线未满足（非强制拦截）。 */
    @GetMapping("/{id}/closeout-check")
    public Result<com.ipd.toolbox.service.ClosureService.CloseoutCheck> closeoutCheck(@PathVariable Long id) {
        return Result.ok(closureService.check(id));
    }

    /** 周报数据：时间窗内的新增/流转/决策/证据（默认 7 天，上限 90）。 */
    @GetMapping("/{id}/weekly")
    public Result<com.ipd.toolbox.service.WeeklyReportService.Summary> weekly(
            @PathVariable Long id, @RequestParam(defaultValue = "7") int days) {
        return Result.ok(weeklyReportService.summary(id, days));
    }

    @GetMapping
    public Result<List<Project>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<Project> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<Project> create(@RequestBody Project project) {
        return Result.ok(service.create(project));
    }

    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable Long id, @RequestBody Project project) {
        return Result.ok(service.update(id, project));
    }
}
