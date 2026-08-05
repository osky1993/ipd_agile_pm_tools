package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.service.IterationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/iterations")
public class IterationController {

    private final IterationService service;

    public IterationController(IterationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Iteration>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /** 迭代复盘：承诺 vs 完成、溢出/移出、速度趋势。 */
    @GetMapping("/{id}/retro")
    public Result<IterationService.Retro> retro(@PathVariable Long id) {
        return Result.ok(service.retro(id));
    }

    @PostMapping
    public Result<Iteration> create(@RequestBody Iteration it) {
        return Result.ok(service.create(it));
    }

    @PutMapping("/{id}")
    public Result<Iteration> update(@PathVariable Long id, @RequestBody Iteration it) {
        return Result.ok(service.update(id, it));
    }

    @GetMapping("/{id}/items")
    public Result<List<WorkItem>> items(@PathVariable Long id) {
        return Result.ok(service.items(id));
    }

    @PostMapping("/{id}/assign/{workItemId}")
    public Result<Void> assign(@PathVariable Long id, @PathVariable Long workItemId) {
        service.assign(id, workItemId);
        return Result.ok();
    }

    @DeleteMapping("/items/{workItemId}")
    public Result<Void> remove(@PathVariable Long workItemId) {
        service.remove(workItemId);
        return Result.ok();
    }
}
