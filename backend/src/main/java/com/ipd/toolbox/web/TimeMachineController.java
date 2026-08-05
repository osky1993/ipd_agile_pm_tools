package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.TimeMachineService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/timemachine")
public class TimeMachineController {

    private final TimeMachineService service;

    public TimeMachineController(TimeMachineService service) {
        this.service = service;
    }

    /** 事件轨道：项目全程范围 + 决策/基线/迭代标记。 */
    @GetMapping("/timeline")
    public Result<TimeMachineService.Timeline> timeline(@RequestParam Long projectId) {
        return Result.ok(service.timeline(projectId));
    }

    /** 时点重建：date 当天收盘时的项目状态（回放状态时间线）。 */
    @GetMapping("/as-of")
    public Result<TimeMachineService.AsOf> asOf(
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(service.asOf(projectId, date));
    }
}
