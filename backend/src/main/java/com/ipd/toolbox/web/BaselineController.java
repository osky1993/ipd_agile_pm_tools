package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Baseline;
import com.ipd.toolbox.domain.entity.BaselineItem;
import com.ipd.toolbox.service.BaselineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/baselines")
public class BaselineController {

    private final BaselineService service;

    public BaselineController(BaselineService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Baseline>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        Baseline b = service.get(id);
        List<BaselineItem> items = service.items(id);
        return Result.ok(Map.of("baseline", b, "items", items));
    }

    /** 当前 vs 基线对比：范围蔓延/移除/日期偏差/估算漂移。 */
    @GetMapping("/{id}/diff")
    public Result<BaselineService.Diff> diff(@PathVariable Long id) {
        return Result.ok(service.diff(id));
    }

    public record CreateRequest(Long projectId, String name) {
    }

    /** 手动建立基线（PM）；DCP 评审通过时的自动基线由评审流程内部触发。 */
    @PostMapping
    public Result<Baseline> create(@RequestBody CreateRequest req) {
        return Result.ok(service.create(req.projectId(), req.name(), "MANUAL", null, null));
    }
}
