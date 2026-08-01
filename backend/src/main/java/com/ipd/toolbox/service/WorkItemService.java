package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.mapper.WorkItemStatusLogMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.statemachine.StateMachine;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkItemService {

    private final WorkItemMapper mapper;
    private final WorkItemStatusLogMapper statusLogMapper;
    private final ProjectMapper projectMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final TraceLinkService traceLinkService;
    private final List<TransitionGuard> guards;

    public WorkItemService(WorkItemMapper mapper, WorkItemStatusLogMapper statusLogMapper,
                           ProjectMapper projectMapper, TraceLinkMapper traceLinkMapper,
                           CodeGenerator codeGenerator, AuditService audit,
                           TraceLinkService traceLinkService, List<TransitionGuard> guards) {
        this.mapper = mapper;
        this.statusLogMapper = statusLogMapper;
        this.projectMapper = projectMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.traceLinkService = traceLinkService;
        this.guards = guards;
    }

    /** 需求树节点（能力→需求→故事/任务），children 递归嵌套。 */
    public record TreeNode(Long id, String code, String type, String title, String status,
                           String priority, List<TreeNode> children) {
    }

    public List<WorkItem> list(Long projectId, String type) {
        QueryWrapper<WorkItem> qw = new QueryWrapper<WorkItem>().eq("project_id", projectId);
        if (type != null && !type.isBlank()) {
            qw.eq("type", type);
        }
        qw.orderByDesc("created_at");
        return mapper.selectList(qw);
    }

    public WorkItem get(Long id) {
        WorkItem item = mapper.selectById(id);
        if (item == null) {
            throw new BusinessException(4040, "工作项不存在");
        }
        return item;
    }

    public List<WorkItemStatusLog> statusHistory(Long id) {
        return statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                .eq("work_item_id", id).orderByAsc("at"));
    }

    /** 当前状态可流转到的目标状态，供前端渲染状态操作按钮。 */
    public Set<String> nextStatuses(Long id) {
        WorkItem item = get(id);
        return StateMachine.nextStatuses(WorkItemType.of(item.getType()), item.getStatus());
    }

    @Transactional
    public WorkItem create(WorkItem item) {
        return create(item, null);
    }

    /** 创建工作项；parentId 非空时同时建立 父 -parent_of-> 新建项 追溯（供需求树从节点建子项）。 */
    @Transactional
    public WorkItem create(WorkItem item, Long parentId) {
        WorkItemType type = WorkItemType.of(item.getType());
        Project project = projectMapper.selectById(item.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在: " + item.getProjectId());
        }
        Long uid = UserContext.currentUserId();
        item.setId(null);
        item.setCode(codeGenerator.next(project.getId(), project.getCode(), type.abbr()));
        item.setStatus(StateMachine.initialStatus(type));
        item.setCreatedBy(uid);
        item.setUpdatedBy(uid);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        item.setDeleted(0);
        mapper.insert(item);

        WorkItemStatusLog log = new WorkItemStatusLog();
        log.setWorkItemId(item.getId());
        log.setFromStatus(null);
        log.setToStatus(item.getStatus());
        log.setActorId(uid);
        log.setReason("创建");
        log.setAt(LocalDateTime.now());
        statusLogMapper.insert(log);

        audit.record(item.getProjectId(), "WORK_ITEM", item.getId(), "CREATE",
                "创建" + type.label() + " " + item.getCode() + " " + item.getTitle(), null, item);

        if (parentId != null) {
            TraceLink link = new TraceLink();
            link.setProjectId(item.getProjectId());
            link.setSourceType("WORK_ITEM");
            link.setSourceId(parentId);
            link.setTargetType("WORK_ITEM");
            link.setTargetId(item.getId());
            link.setRelation("parent_of");
            traceLinkService.create(link);
        }
        return item;
    }

    /** 构建能力→需求→故事/任务的树（父子边=parent_of 追溯，孤立节点作为根）。 */
    public List<TreeNode> tree(Long projectId) {
        List<WorkItem> items = mapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId)
                .in("type", WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
                        WorkItemType.STORY.name(), WorkItemType.TASK.name())
                .orderByAsc("id"));
        Map<Long, WorkItem> byId = new LinkedHashMap<>();
        for (WorkItem w : items) {
            byId.put(w.getId(), w);
        }
        List<TraceLink> links = traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId)
                .eq("relation", "parent_of")
                .eq("source_type", "WORK_ITEM")
                .eq("target_type", "WORK_ITEM"));
        // parent -> [child]，仅保留两端都在本类型集合内的边
        Map<Long, List<Long>> childrenMap = new LinkedHashMap<>();
        Set<Long> hasParent = new java.util.HashSet<>();
        for (TraceLink l : links) {
            if (byId.containsKey(l.getSourceId()) && byId.containsKey(l.getTargetId())) {
                childrenMap.computeIfAbsent(l.getSourceId(), k -> new ArrayList<>()).add(l.getTargetId());
                hasParent.add(l.getTargetId());
            }
        }
        List<TreeNode> roots = new ArrayList<>();
        for (WorkItem w : items) {
            if (!hasParent.contains(w.getId())) {
                roots.add(buildNode(w, byId, childrenMap, new java.util.HashSet<>()));
            }
        }
        return roots;
    }

    private TreeNode buildNode(WorkItem w, Map<Long, WorkItem> byId,
                               Map<Long, List<Long>> childrenMap, Set<Long> visited) {
        visited.add(w.getId());
        List<TreeNode> children = new ArrayList<>();
        for (Long childId : childrenMap.getOrDefault(w.getId(), List.of())) {
            if (!visited.contains(childId) && byId.containsKey(childId)) {
                children.add(buildNode(byId.get(childId), byId, childrenMap, visited));
            }
        }
        return new TreeNode(w.getId(), w.getCode(), w.getType(), w.getTitle(),
                w.getStatus(), w.getPriority(), children);
    }

    @Transactional
    public WorkItem update(Long id, WorkItem patch) {
        WorkItem old = get(id);
        WorkItem before = shallowCopy(old);
        if (patch.getTitle() != null) old.setTitle(patch.getTitle());
        if (patch.getDescription() != null) old.setDescription(patch.getDescription());
        if (patch.getOwnerId() != null) old.setOwnerId(patch.getOwnerId());
        if (patch.getPriority() != null) old.setPriority(patch.getPriority());
        if (patch.getProductVersionId() != null) old.setProductVersionId(patch.getProductVersionId());
        if (patch.getIterationId() != null) old.setIterationId(patch.getIterationId());
        if (patch.getBaselineDate() != null) old.setBaselineDate(patch.getBaselineDate());
        if (patch.getForecastDate() != null) old.setForecastDate(patch.getForecastDate());
        if (patch.getAcceptanceCriteria() != null) old.setAcceptanceCriteria(patch.getAcceptanceCriteria());
        if (patch.getEstimate() != null) old.setEstimate(patch.getEstimate());
        if (patch.getExtFields() != null) old.setExtFields(patch.getExtFields());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);

        boolean ownerChanged = patch.getOwnerId() != null
                && !patch.getOwnerId().equals(before.getOwnerId());
        audit.record(old.getProjectId(), "WORK_ITEM", id,
                ownerChanged ? "OWNER_CHANGE" : "UPDATE",
                "更新 " + old.getCode(), before, old);
        return old;
    }

    /**
     * 状态流转：状态机校验 → 守卫校验 → 更新 → 写状态时间线 → 审计。
     * 进入 Accepted 时自动补齐验收人与时间。
     */
    @Transactional
    public WorkItem transition(Long id, String toStatus, String reason) {
        WorkItem item = get(id);
        WorkItemType type = WorkItemType.of(item.getType());
        String from = item.getStatus();

        if (from.equals(toStatus)) {
            throw new BusinessException("目标状态与当前状态相同");
        }
        if (!StateMachine.canTransition(type, from, toStatus)) {
            throw new BusinessException(4091,
                    "不允许的状态流转：" + from + " → " + toStatus + "（类型 " + type.label() + "）");
        }
        boolean backward = StateMachine.isBackward(type, from, toStatus);
        if (backward && (reason == null || reason.isBlank())) {
            throw new BusinessException("状态回退必须填写理由");
        }

        Long uid = UserContext.currentUserId();
        // 进入 Accepted：先补齐验收人/时间，再过守卫
        if ("Accepted".equals(toStatus)) {
            item.setAcceptedBy(uid);
            item.setAcceptedAt(LocalDateTime.now());
        }

        TransitionContext ctx = new TransitionContext().setReason(reason);
        for (TransitionGuard guard : guards) {
            if (guard.supports(item, toStatus)) {
                guard.check(item, toStatus, ctx);
            }
        }

        item.setStatus(toStatus);
        item.setUpdatedBy(uid);
        item.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(item);

        WorkItemStatusLog log = new WorkItemStatusLog();
        log.setWorkItemId(id);
        log.setFromStatus(from);
        log.setToStatus(toStatus);
        log.setActorId(uid);
        log.setReason(reason);
        log.setAt(LocalDateTime.now());
        statusLogMapper.insert(log);

        audit.record(item.getProjectId(), "WORK_ITEM", id, "STATUS_CHANGE",
                item.getCode() + " 状态 " + from + " → " + toStatus
                        + (reason != null ? "（" + reason + "）" : ""),
                Set.of("status", from), Set.of("status", toStatus));
        return item;
    }

    private WorkItem shallowCopy(WorkItem s) {
        WorkItem c = new WorkItem();
        c.setId(s.getId());
        c.setCode(s.getCode());
        c.setTitle(s.getTitle());
        c.setStatus(s.getStatus());
        c.setOwnerId(s.getOwnerId());
        c.setPriority(s.getPriority());
        return c;
    }
}
