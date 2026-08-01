package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.ChangeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/changes")
public class ChangeController {

    private final ChangeService service;

    public ChangeController(ChangeService service) {
        this.service = service;
    }

    @PostMapping("/{id}/analyze")
    public Result<ChangeService.ImpactResult> analyze(@PathVariable Long id) {
        return Result.ok(service.analyze(id));
    }

    public record DecideRequest(boolean approve, String reason) {
    }

    @PostMapping("/{id}/decide")
    public Result<Decision> decide(@PathVariable Long id, @RequestBody DecideRequest req) {
        return Result.ok(service.decide(id, req.approve(), req.reason()));
    }
}
