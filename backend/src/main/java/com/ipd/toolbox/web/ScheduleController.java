package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.ScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    /** 关键路径推演：今天=第 0 天的相对 CPM（ES/EF/LS/LF/浮动/关键链）。 */
    @GetMapping("/critical-path")
    public Result<ScheduleService.CpmResult> criticalPath(@RequestParam Long projectId) {
        return Result.ok(service.criticalPath(projectId));
    }
}
