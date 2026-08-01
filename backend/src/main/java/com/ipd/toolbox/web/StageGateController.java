package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.service.StageGateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stage-gates")
public class StageGateController {

    private final StageGateService service;

    public StageGateController(StageGateService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<StageGate>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    @PostMapping
    public Result<StageGate> create(@RequestBody StageGate g) {
        return Result.ok(service.create(g));
    }

    @PutMapping("/{id}")
    public Result<StageGate> update(@PathVariable Long id, @RequestBody StageGate g) {
        return Result.ok(service.update(id, g));
    }
}
