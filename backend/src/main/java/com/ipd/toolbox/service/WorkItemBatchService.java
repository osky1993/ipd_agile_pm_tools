package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.WorkItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作项批量操作编排：逐条调用单条 Service（各自独立事务），
 * 守卫全走、审计全留，逐条成败互不影响，结果逐条返回。
 * 故意不加 @Transactional——部分成功是期望语义，失败行单独暴露给用户处理。
 */
@Service
public class WorkItemBatchService {

    /** UPDATE 动作的字段补丁（null = 不改） */
    public record BatchPatch(Long ownerId, String priority) {
    }

    /**
     * action: TRANSITION（toStatus/reason）| UPDATE（patch）| ASSIGN_ITERATION（iterationId，走 Ready 守卫）。
     * dryRun=true 时仅预演校验（状态机/守卫全跑，不落库），逐条返回"会不会被拦、为什么"。
     */
    public record BatchRequest(List<Long> ids, String action, String toStatus, String reason,
                               Long iterationId, BatchPatch patch, boolean dryRun) {
    }

    public record BatchItemResult(Long id, String code, boolean ok, String message) {
    }

    /** 批量创建的单条条目（parentCode 可选：按编号挂 parent_of 到已有父项） */
    public record CreateItem(String type, String title, String description, String priority,
                             String acceptanceCriteria, String estimate, String parentCode) {
    }

    /** dryRun=true 只校验并预览，不落库（AI 拆条场景的安全阀） */
    public record BatchCreateRequest(Long projectId, boolean dryRun, List<CreateItem> items) {
    }

    private static final java.util.Set<String> CREATE_TYPES = java.util.Set.of(
            "CAPABILITY", "REQUIREMENT", "STORY", "TASK", "DEFECT", "RISK", "CHANGE");

    private final WorkItemService workItemService;
    private final IterationService iterationService;
    private final com.ipd.toolbox.mapper.WorkItemMapper workItemMapper;

    /**
     * 批量服务依赖注入。
     * 复用 workItemService/iterationService 的现有守卫与审计口径；
     * workItemMapper 仅用于父项查找（通过 parentCode）。
     */
    public WorkItemBatchService(WorkItemService workItemService, IterationService iterationService,
                                com.ipd.toolbox.mapper.WorkItemMapper workItemMapper) {
        this.workItemService = workItemService;
        this.iterationService = iterationService;
        this.workItemMapper = workItemMapper;
    }

    /** 批量创建：逐条走 create（编号/初始状态/审计全生效）；dryRun 只校验返回预览。 */
    public List<BatchItemResult> batchCreate(BatchCreateRequest req) {
        if (req == null || req.projectId() == null || req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("批量创建需指定项目与条目");
        }
        List<BatchItemResult> out = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            CreateItem it = req.items().get(i);
            String label = "#" + (i + 1) + " " + (it.title() == null ? "" : it.title());
            try {
                if (it.title() == null || it.title().isBlank()) {
                    throw new BusinessException("标题不能为空");
                }
                String type = it.type() == null ? "" : it.type().toUpperCase();
                if (!CREATE_TYPES.contains(type)) {
                    throw new BusinessException("类型须为 " + Labels.options(CREATE_TYPES, Labels::workItemType)
                            + " 之一，当前: " + it.type());
                }
                Long parentId = null;
                if (it.parentCode() != null && !it.parentCode().isBlank()) {
                    WorkItem parent = workItemMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WorkItem>()
                                    .eq("project_id", req.projectId()).eq("code", it.parentCode()));
                    if (parent == null) {
                        throw new BusinessException("父项编号不存在: " + it.parentCode());
                    }
                    parentId = parent.getId();
                }
                if (req.dryRun()) {
                    out.add(new BatchItemResult(null, null, true,
                            "[预览] " + Labels.workItemType(type) + " " + it.title()
                                    + (parentId != null ? "（挂 " + it.parentCode() + " 下）" : "")));
                    continue;
                }
                WorkItem w = new WorkItem();
                w.setProjectId(req.projectId());
                w.setType(type);
                w.setTitle(it.title().trim());
                w.setDescription(it.description());
                w.setPriority(it.priority());
                w.setAcceptanceCriteria(it.acceptanceCriteria());
                w.setEstimate(it.estimate());
                WorkItem created = workItemService.create(w, parentId);
                out.add(new BatchItemResult(created.getId(), created.getCode(), true, "OK"));
            } catch (BusinessException e) {
                out.add(new BatchItemResult(null, label, false, e.getMessage()));
            }
        }
        return out;
    }

    /**
     * 执行批量动作（流转/更新/进入迭代）。
     *
     * <p>核心原则：按 id 逐条执行，单条失败不影响其他条目。
     * 支持 dryRun 模式做预检：
     * 1) TRANSITION: 调用 preflight 触发状态机守卫校验，但不落库；
     * 2) UPDATE: 只在非 dryRun 下执行实际字段更新；
     * 3) ASSIGN_ITERATION: 根据模式区分预检/提交。
     * 每条结果返回成功与否及明细，便于前端展示“局部成功、局部失败”。</p>
     *
     * @param req 批量请求，包含动作类型、目标对象与参数
     * @return 每条记录的执行结果
     * @throws BusinessException 当请求参数为空、动作不支持或必要参数缺失时抛出
     */
    public List<BatchItemResult> execute(BatchRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new BusinessException("批量操作对象不能为空");
        }
        String action = req.action() == null ? "" : req.action();
        switch (action) {
            case "TRANSITION" -> {
                if (req.toStatus() == null || req.toStatus().isBlank()) {
                    throw new BusinessException("批量流转需指定目标状态");
                }
            }
            case "UPDATE" -> {
                if (req.patch() == null || (req.patch().ownerId() == null && req.patch().priority() == null)) {
                    throw new BusinessException("批量更新需至少指定一个字段");
                }
            }
            case "ASSIGN_ITERATION" -> {
                if (req.iterationId() == null) {
                    throw new BusinessException("批量进迭代需指定迭代");
                }
            }
            default -> throw new BusinessException("未知批量动作: " + action);
        }

        List<BatchItemResult> out = new ArrayList<>();
        for (Long id : req.ids()) {
            String code = null;
            try {
                code = workItemService.get(id).getCode();
                switch (action) {
                    case "TRANSITION" -> {
                        if (req.dryRun()) {
                            workItemService.preflight(id, req.toStatus(), req.reason());
                        } else {
                            workItemService.transition(id, req.toStatus(), req.reason());
                        }
                    }
                    case "UPDATE" -> {
                        if (!req.dryRun()) {
                            WorkItem patch = new WorkItem();
                            patch.setOwnerId(req.patch().ownerId());
                            patch.setPriority(req.patch().priority());
                            workItemService.update(id, patch);
                        }
                    }
                    case "ASSIGN_ITERATION" -> {
                        if (req.dryRun()) {
                            iterationService.preflightAssign(req.iterationId(), id);
                        } else {
                            iterationService.assign(req.iterationId(), id);
                        }
                    }
                }
                out.add(new BatchItemResult(id, code, true, req.dryRun() ? "[预检通过]" : "OK"));
            } catch (BusinessException e) { // GuardException 亦是其子类
                out.add(new BatchItemResult(id, code, false, e.getMessage()));
            }
        }
        return out;
    }
}
