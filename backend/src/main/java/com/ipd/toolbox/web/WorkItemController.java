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
/**
 * 工作项控制器：覆盖“列表/检索/导入/状态流转/追溯关系/审计”的全链路。
 * 设计约束：
 * - 入口参数尽量薄，不在控制器做复杂业务判断
 * - 返回统一 Result，让前端以 code/message/data 解包
 * - 需要鉴权动作已在 service 的 requireRole 中固化
 */
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

    /**
     * 批量执行工作项操作（创建/流转/更新/归属挂接）。
     *
     * <p>用途：供导入回填、脚本修正和 AI 辅助批量更新复用，减少多次请求带来的网络开销。
     *
     * <p>请求特征：
     * <ul>
     *   <li>每个操作条目独立执行并返回单条结果。</li>
     *   <li>失败条目不会阻断其他条目，返回列表聚合执行结果。</li>
     *   <li>服务层按条目原子化处理，保障“局部成功 + 局部失败”可读性。</li>
     * </ul>
     *
     * <p>返回：List&lt;BatchItemResult&gt;，每条记录包含原始命令与执行成败。
     * <p>副作用：批量变更数据库，具备幂等重放语义前提下可用于重试（同一条目重复提交时以服务层幂等策略为准）。
     */
    @PostMapping("/batch")
    public Result<List<WorkItemBatchService.BatchItemResult>> batch(
            @RequestBody WorkItemBatchService.BatchRequest req) {
        return Result.ok(batchService.execute(req));
    }

    /**
     * 批量创建工作项（AI/脚本主入口）。
     *
     * <p>用途：支持一次性提交多条创建任务，适合模型生成或外部工具回填场景。
     *
     * <p>关键行为：
     * <ul>
     *   <li>请求可带 dryRun 标记用于预演，预演阶段仅返回变更意向。</li>
     *   <li>默认建议 dryRun=true，防止误导入。</li>
     *   <li>落库时逐条返回执行明细，不做“一致成功/失败”硬性封装。</li>
     * </ul>
     *
     * <p>更新粒度：每条请求可能创建一个工作项及其树关系，异常时仅对应条目失败。
     */
    @PostMapping("/batch-create")
    public Result<List<WorkItemBatchService.BatchItemResult>> batchCreate(
            @RequestBody WorkItemBatchService.BatchCreateRequest req) {
        return Result.ok(batchService.batchCreate(req));
    }

    /**
     * 查询项目工作项列表。
     *
     * <p>用途：工作台列表页/下拉选择的数据源。
     *
     * @param projectId 项目 ID，必填
     * @param type 工作项类型，可空（空表示所有类型）
     * @return 按服务端约定排序的工作项列表
     *
     * <p>副作用：无。
     */
    @GetMapping
    public Result<List<WorkItem>> list(@RequestParam Long projectId,
                                       @RequestParam(required = false) String type) {
        return Result.ok(service.list(projectId, type));
    }

    /**
     * 获取需求树结构。
     *
     * <p>用途：前端树组件直接渲染层级关系；返回字段为扁平关系树节点。
     *
     * @param projectId 项目 ID
     * @return 树节点数组
     * <p>副作用：无（纯读）。
     */
    @GetMapping("/tree")
    public Result<List<WorkItemService.TreeNode>> tree(@RequestParam Long projectId) {
        return Result.ok(service.tree(projectId));
    }

    /**
     * 全局搜索工作项（编号/标题模糊）。
     *
     * <p>用途：快速定位历史条目，默认返回上限 20 条用于输入提示。
     *
     * @param q 查询关键字
     * @return 关键字匹配结果
     * <p>边界：q 为空会被参数层/服务层拦截。
     */
    @GetMapping("/search")
    public Result<List<WorkItem>> search(@RequestParam String q) {
        return Result.ok(service.search(q));
    }

    /**
     * 下载能力/需求树 Excel 导入模板。
     *
     * <p>用途：标准化列头和示例，避免导入字段错位。
     *
     * @return 二进制流，Content-Disposition 为 attachment
     * <p>副作用：无。
     */
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

    /**
     * 导入风险/变更 Excel。
     *
     * <p>用途：从外部表格批量创建/更新风险与变更。
     * <p>更新粒度：按每行解析为业务对象后执行持久化，失败行返回摘要；不保证全量原子性。
     *
     * <p>失败策略：
     * <ul>
     *   <li>文件不可读/类型错误：直接抛异常返回失败。</li>
     *   <li>单行数据缺陷：服务层按行记录入参错误并继续处理可配置部分行。</li>
     * </ul>
     *
     * <p>返回：包含总条数/成功/失败详情的 Map，可用于导入后的补正。
     */
    @PostMapping("/import-excel")
    public Result<Map<String, Object>> importExcel(
            @RequestParam Long projectId, @RequestParam String type,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        return Result.ok(riskChangeExcelService.importExcel(projectId, type, file.getInputStream()));
    }

    /**
     * 导入能力/需求树 Excel。
     *
     * <p>用途：支持层级编号（1/1.1/1.1.1）构建 parent-child 关系。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>新增/更新树节点关系。</li>
     *   <li>按行消费并写入项目内工作项。</li>
     * </ul>
     *
     * <p>返回：标准导入摘要（成功/失败明细）用于二次确认。
     */
    @PostMapping("/import-tree")
    public Result<java.util.Map<String, Object>> importTree(
            @RequestParam Long projectId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return Result.ok(treeImportService.importExcel(projectId, file.getInputStream()));
    }

    /**
     * CSV 批量导入。
     *
     * <p>用途：历史数据回填和快速演练；第一行为标题头自动跳过。
     * <p>列结构：type,标题,描述,优先级,验收条件,估算。
     *
     * <p>边界与失败策略：
     * <ul>
     *   <li>按 UTF-8 读取文本。</li>
     *   <li>单行解析失败可导致整个批量失败，具体行为由服务层实现约束。</li>
     * </ul>
     */
    @PostMapping("/import")
    public Result<java.util.Map<String, Object>> importCsv(
            @RequestParam Long projectId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return Result.ok(service.importCsv(projectId,
                new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * 查询单个工作项详情。
     *
     * @param id 工作项 ID
     * @return 目标工作项完整实体
     * <p>用途：详情抽屉/编辑面板复用。
     * <p>副作用：无。
     */
    @GetMapping("/{id}")
    public Result<WorkItem> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /**
     * 创建工作项。
     *
     * <p>用途：新增能力/需求/任务等基础对象。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>插入工作项主表。</li>
     *   <li>如 parentId 非空，建立树结构父子关系。</li>
     *   <li>状态机初始状态由服务层规范化。</li>
     * </ul>
     *
     * <p>失败回滚：服务层事务内失败不应产生半成品记录。
     */
    @PostMapping
    public Result<WorkItem> create(@RequestBody WorkItem item,
                                   @RequestParam(required = false) Long parentId) {
        return Result.ok(service.create(item, parentId));
    }

    /**
     * 更新工作项字段。
     *
     * <p>用途：补齐标题/描述/状态以外的字段，仅处理请求体中显式携带字段。
     *
     * <p>边界：
     * <ul>
     *   <li>空值通常按“保留旧值”策略处理（非覆盖空值），取决于 service 实现。</li>
     *   <li>对状态类变更请使用 transition 接口，避免混合路径。</li>
     * </ul>
     */
    @PutMapping("/{id}")
    public Result<WorkItem> update(@PathVariable Long id, @RequestBody WorkItem item) {
        return Result.ok(service.update(id, item));
    }

    public record TransitionRequest(String toStatus, String reason) {
    }

    /**
     * 执行工作项状态迁移。
     *
     * <p>用途：统一入口，代替直接改状态字段，触发守卫与状态日志链路。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>写状态变更日志。</li>
     *   <li>检查迁移合法性、回退原因、鉴权与可选副作用（如派生字段回填）。</li>
     * </ul>
     *
     * <p>返回：迁移后的最新实体；失败时返回统一错误码（非法迁移/守卫拦截）。
     */
    @PostMapping("/{id}/transition")
    public Result<WorkItem> transition(@PathVariable Long id, @RequestBody TransitionRequest req) {
        return Result.ok(service.transition(id, req.toStatus(), req.reason()));
    }

    /**
     * 查询工作项可迁移的下一状态集合。
     *
     * @param id 工作项 ID
     * @return 可迁移状态集合（无权限/不可迁移时可能为空）
     * <p>副作用：无。
     */
    @GetMapping("/{id}/next-statuses")
    public Result<Set<String>> nextStatuses(@PathVariable Long id) {
        return Result.ok(service.nextStatuses(id));
    }

    /**
     * 查询状态时间线。
     *
     * <p>用途：复盘、追责、变更审计展示。
     * <p>返回：按时间排序的状态变更历史记录。
     * <p>副作用：无。
     */
    @GetMapping("/{id}/status-history")
    public Result<List<WorkItemStatusLog>> statusHistory(@PathVariable Long id) {
        return Result.ok(service.statusHistory(id));
    }

    /**
     * 查询工作项审计日志。
     *
     * <p>用途：拉取 CREATE/UPDATE/TRANSITION 等动作审计，支持操作追踪与争议仲裁。
     * <p>副作用：只读。
     */
    @GetMapping("/{id}/audit")
    public Result<List<AuditEvent>> audit(@PathVariable Long id) {
        return Result.ok(auditService.listByEntity("WORK_ITEM", id));
    }

    /**
     * 查询工作项追溯链（入/出边）。
     *
     * <p>用途：一页显示该工作项与需求/测试/证据的关系边。
     *
     * @return 追溯视图集合；用于 UI 图谱与风控指标联动。
     * <p>副作用：无。
     */
    @GetMapping("/{id}/traces")
    public Result<List<TraceLinkService.TraceView>> traces(@PathVariable Long id) {
        return Result.ok(traceLinkService.around("WORK_ITEM", id));
    }
}
