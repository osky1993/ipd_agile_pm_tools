package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.service.RiskService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risks")
public class RiskController {

    private final RiskService service;

    public RiskController(RiskService service) {
        this.service = service;
    }

    /** 风险任务化：按处置措施生成应对 TASK（TASK -affects→ RISK，防重）。 */
    @PostMapping("/{id}/mitigation-task")
    public Result<WorkItem> mitigationTask(@PathVariable Long id) {
        return Result.ok(service.createMitigationTask(id));
    }
}
