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
    private final com.ipd.toolbox.service.CriterionTemplateService templateService;

    public GateCriterionController(GateCriterionService service,
                                   com.ipd.toolbox.service.CriterionTemplateService templateService) {
        this.service = service;
        this.templateService = templateService;
    }

    /** DCP 条件模板库（各决策评审点的典型条件，含红线标记）。 */
    @GetMapping("/templates")
    public Result<List<com.ipd.toolbox.service.CriterionTemplateService.Template>> templates() {
        return Result.ok(templateService.templates());
    }

    public record ApplyTemplateRequest(Long projectId, Long stageGateId, String templateKey) {
    }

    /** 一键铺条件：应用模板到指定 gate，同文本条件自动跳过（可重复应用）。 */
    @PostMapping("/apply-template")
    public Result<java.util.Map<String, Object>> applyTemplate(@RequestBody ApplyTemplateRequest req) {
        return Result.ok(templateService.apply(req.projectId(), req.stageGateId(), req.templateKey()));
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
