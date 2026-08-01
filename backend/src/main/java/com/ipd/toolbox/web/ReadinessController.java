package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.service.ReadinessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/readiness")
public class ReadinessController {

    private final ReadinessService service;

    public ReadinessController(ReadinessService service) {
        this.service = service;
    }

    @GetMapping("/domains")
    public Result<List<String>> domains() {
        return Result.ok(ReadinessService.DOMAINS);
    }

    @GetMapping("/items")
    public Result<List<GateCriterion>> items(@RequestParam Long projectId,
                                             @RequestParam(required = false) String domain) {
        return Result.ok(service.items(projectId, domain));
    }

    @GetMapping("/summary")
    public Result<ReadinessService.Summary> summary(@RequestParam Long projectId) {
        return Result.ok(service.summary(projectId));
    }
}
