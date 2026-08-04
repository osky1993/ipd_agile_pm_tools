package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.AuditEvent;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.service.AuditService;
import com.ipd.toolbox.service.TraceLinkService;
import com.ipd.toolbox.service.WorkItemBatchService;
import com.ipd.toolbox.service.WorkItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/work-items")
public class WorkItemController {

    private final WorkItemService service;
    private final com.ipd.toolbox.service.TreeImportService treeImportService;
    private final AuditService auditService;
    private final TraceLinkService traceLinkService;
    private final WorkItemBatchService batchService;
    private final com.ipd.toolbox.service.RiskChangeExcelService riskChangeExcelService;

    public WorkItemController(WorkItemService service, AuditService auditService,
                              TraceLinkService traceLinkService,
                              com.ipd.toolbox.service.TreeImportService treeImportService,
                              WorkItemBatchService batchService,
                              com.ipd.toolbox.service.RiskChangeExcelService riskChangeExcelService) {
        this.service = service;
        this.treeImportService = treeImportService;
        this.auditService = auditService;
        this.traceLinkService = traceLinkService;
        this.batchService = batchService;
        this.riskChangeExcelService = riskChangeExcelService;
    }

    /** 批量操作：流转/改字段/进迭代。逐条独立事务，返回逐条成败。 */
    @PostMapping("/batch")
    public Result<List<WorkItemBatchService.BatchItemResult>> batch(
            @RequestBody WorkItemBatchService.BatchRequest req) {
        return Result.ok(batchService.execute(req));
    }

    /** 批量创建（MCP/AI 拆条入口）：dryRun 默认只预览，显式 false 才落库。 */
    @PostMapping("/batch-create")
    public Result<List<WorkItemBatchService.BatchItemResult>> batchCreate(
            @RequestBody WorkItemBatchService.BatchCreateRequest req) {
        return Result.ok(batchService.batchCreate(req));
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

    /** 全局搜索（编号/标题模糊，跨项目，最多 20 条）。 */
    @GetMapping("/search")
    public Result<List<WorkItem>> search(@RequestParam String q) {
        return Result.ok(service.search(q));
    }

    /** 能力与需求树 Excel 导入模板下载。 */
    @GetMapping("/import-template.xlsx")
    public org.springframework.http.ResponseEntity<byte[]> importTemplate() {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=requirement-tree-template.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(treeImportService.template());
    }

    /** 风险/变更 Excel 导入模板下载。 */
    @GetMapping("/import-excel-template.xlsx")
    public org.springframework.http.ResponseEntity<byte[]> importExcelTemplate(@RequestParam String type) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + type.toLowerCase() + "-import-template.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(riskChangeExcelService.template(type));
    }

    /** 风险/变更 Excel 导入（RISK 的处置措施/期限写 ext_fields）。 */
    @PostMapping("/import-excel")
    public Result<Map<String, Object>> importExcel(
            @RequestParam Long projectId, @RequestParam String type,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        return Result.ok(riskChangeExcelService.importExcel(projectId, type, file.getInputStream()));
    }

    /** 能力与需求树 Excel 导入（层级序号 1/1.1/1.1.1 表达树）。 */
    @PostMapping("/import-tree")
    public Result<java.util.Map<String, Object>> importTree(
            @RequestParam Long projectId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return Result.ok(treeImportService.importExcel(projectId, file.getInputStream()));
    }

    /** CSV 批量导入（列：类型,标题,描述,优先级,验收条件,估算）。 */
    @PostMapping("/import")
    public Result<java.util.Map<String, Object>> importCsv(
            @RequestParam Long projectId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return Result.ok(service.importCsv(projectId,
                new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8)));
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
