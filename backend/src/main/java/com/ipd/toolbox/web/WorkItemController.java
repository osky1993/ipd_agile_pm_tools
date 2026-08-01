package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.AuditEvent;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.service.AuditService;
import com.ipd.toolbox.service.TraceLinkService;
import com.ipd.toolbox.service.WorkItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/work-items")
public class WorkItemController {

    private final WorkItemService service;
    private final AuditService auditService;
    private final TraceLinkService traceLinkService;

    public WorkItemController(WorkItemService service, AuditService auditService,
                              TraceLinkService traceLinkService) {
        this.service = service;
        this.auditService = auditService;
        this.traceLinkService = traceLinkService;
    }

    @GetMapping
    public Result<List<WorkItem>> list(@RequestParam Long projectId,
                                       @RequestParam(required = false) String type) {
        return Result.ok(service.list(projectId, type));
    }

    @GetMapping("/tree")
    public Result<List<WorkItemService.TreeNode>> tree(@RequestParam Long projectId) {
        return Result.ok(service.tree(projectId));
    }

    @GetMapping("/{id}")
    public Result<WorkItem> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<WorkItem> create(@RequestBody WorkItem item,
                                   @RequestParam(required = false) Long parentId) {
        return Result.ok(service.create(item, parentId));
    }

    @PutMapping("/{id}")
    public Result<WorkItem> update(@PathVariable Long id, @RequestBody WorkItem item) {
        return Result.ok(service.update(id, item));
    }

    public record TransitionRequest(String toStatus, String reason) {
    }

    @PostMapping("/{id}/transition")
    public Result<WorkItem> transition(@PathVariable Long id, @RequestBody TransitionRequest req) {
        return Result.ok(service.transition(id, req.toStatus(), req.reason()));
    }

    @GetMapping("/{id}/next-statuses")
    public Result<Set<String>> nextStatuses(@PathVariable Long id) {
        return Result.ok(service.nextStatuses(id));
    }

    @GetMapping("/{id}/status-history")
    public Result<List<WorkItemStatusLog>> statusHistory(@PathVariable Long id) {
        return Result.ok(service.statusHistory(id));
    }

    @GetMapping("/{id}/audit")
    public Result<List<AuditEvent>> audit(@PathVariable Long id) {
        return Result.ok(auditService.listByEntity("WORK_ITEM", id));
    }

    @GetMapping("/{id}/traces")
    public Result<List<TraceLinkService.TraceView>> traces(@PathVariable Long id) {
        return Result.ok(traceLinkService.around("WORK_ITEM", id));
    }
}
