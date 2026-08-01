package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.DecisionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final DecisionService service;

    public DecisionController(DecisionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Decision>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    @PostMapping
    public Result<Decision> record(@RequestBody Decision d) {
        return Result.ok(service.record(d));
    }
}
