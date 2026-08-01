package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.service.GateCriterionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/gate-criteria")
public class GateCriterionController {

    private final GateCriterionService service;

    public GateCriterionController(GateCriterionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<GateCriterion>> list(@RequestParam Long projectId,
                                            @RequestParam(required = false) Long stageGateId,
                                            @RequestParam(required = false) Integer isReadiness) {
        return Result.ok(service.list(projectId, stageGateId, isReadiness));
    }

    @PostMapping
    public Result<GateCriterion> create(@RequestBody GateCriterion c) {
        return Result.ok(service.create(c));
    }

    @PutMapping("/{id}")
    public Result<GateCriterion> update(@PathVariable Long id, @RequestBody GateCriterion c) {
        return Result.ok(service.update(id, c));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
