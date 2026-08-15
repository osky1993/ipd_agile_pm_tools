package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.common.Labels;
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
/**
 * 工作项服务（PM 核心）：覆盖工作项的查询、创建、更新、树、导入、状态流转与审计。
 * 关键原则：
 * - service 边界负责规则校验与副作用；controller 只做入参透传
 * - 所有变更写 status log + audit，保证可追溯
 * - 守卫在 stateMachine.validateTransition 内统一执行，避免散点规则
 */
public class WorkItemService {

    private final WorkItemMapper mapper;
    private final WorkItemStatusLogMapper statusLogMapper;
    private final ProjectMapper projectMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final TraceLinkService traceLinkService;
    private final List<TransitionGuard> guards;

    /**
     * 工作项服务核心依赖：工作项与状态日志主表、项目/追溯关系、代码生成、审计与状态机守卫。
     * 状态变更统一走 guards 校验，任何变更都要求可追溯审计和日志留痕。
     */
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

    /**
     * 全局搜索工作项（只读）：
     * <ul>
     *   <li>输入 null/blank 时直接返回空列表，避免将空词扩展成全表扫描。</li>
     *   <li>按 code 与 title 做 OR 模糊匹配，返回同时命中编号和标题的候选集合。</li>
     *   <li>按 id 倒序取前 20 条，作为搜索建议窗口，控制返回体积并稳定前端交互。</li>
     * </ul>
     *
     * @param q 关键字，允许空值
     * @return 命中工作项的投影列表；不包含业务侧排序与分页元信息
     * @throws IllegalArgumentException 入参为 null 时不会抛异常
     */
    public List<WorkItem> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String kw = q.trim();
        return mapper.selectList(new QueryWrapper<WorkItem>()
                .and(w -> w.like("code", kw).or().like("title", kw))
                .orderByDesc("id").last("LIMIT 20"));
    }

    /**
     * CSV 导入（写链路）：
     * <ol>
     *   <li>逐行清洗输入并识别标题行，空行直接跳过。</li>
     *   <li>对每一行执行 <code>create(WorkItem)</code>，保持与单条创建一致的校验与持久化副作用。</li>
     *   <li>成功写入通过内存计数成功行；失败行记录“第 N 行 + 原因”继续处理后续行。</li>
     *   <li>最终返回聚合统计，便于前端展示导入成功率与错误清单。</li>
     * </ol>
     * <p>边界说明：此方法使用事务包裹，但当前实现以 catch 异常并继续执行，通常情况下不会整体回滚；若数据库层发生未捕获异常则整体回滚。</p>
     * <p>更新粒度：每条成功行都会触发编号生成、状态初始化日志、审计记录。</p>
     *
     * @param projectId 目标项目 ID
     * @param csv 行文本（第一行为标题时自动识别）
     * @return Map{created=Integer, errors=List&lt;String&gt;}
     */
    @Transactional
    public Map<String, Object> importCsv(Long projectId, String csv) {
        UserContext.requireRole("PM");
        int created = 0;
        List<String> errors = new ArrayList<>();
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace("﻿", "");
            List<String> cols = parseCsvLine(line);
            if (i == 0 && !cols.isEmpty() && (cols.get(0).contains("类型") || cols.get(0).equalsIgnoreCase("type"))) {
                continue; // 表头
            }
            try {
                if (cols.size() < 2 || cols.get(1).isBlank()) {
                    throw new BusinessException("至少需要 类型,标题 两列");
                }
                WorkItem w = new WorkItem();
                w.setProjectId(projectId);
                w.setType(resolveType(cols.get(0)).name());
                w.setTitle(cols.get(1).trim());
                if (cols.size() > 2 && !cols.get(2).isBlank()) w.setDescription(cols.get(2));
                if (cols.size() > 3 && !cols.get(3).isBlank()) w.setPriority(cols.get(3).trim());
                if (cols.size() > 4 && !cols.get(4).isBlank()) w.setAcceptanceCriteria(cols.get(4));
                if (cols.size() > 5 && !cols.get(5).isBlank()) w.setEstimate(cols.get(5).trim());
                create(w, null);
                created++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("errors", errors);
        return out;
    }

    /** 类型解析：支持枚举名或中文标签（便于业务端手工录入）。 */
    static WorkItemType resolveType(String s) {
        String t = s == null ? "" : s.trim();
        for (WorkItemType wt : WorkItemType.values()) {
            if (wt.name().equalsIgnoreCase(t) || wt.label().equals(t) || wt.abbr().equalsIgnoreCase(t)) {
                return wt;
            }
        }
        throw new BusinessException("未知类型: " + s);
    }

    /** 极简 CSV 行解析：支持双引号包裹（含逗号和转义引号）。 */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 按项目和类型列出工作项，默认倒序显示（创建时间新到旧）。 */
    public List<WorkItem> list(Long projectId, String type) {
        QueryWrapper<WorkItem> qw = new QueryWrapper<WorkItem>().eq("project_id", projectId);
        if (type != null && !type.isBlank()) {
            qw.eq("type", type);
        }
        qw.orderByDesc("created_at");
        return mapper.selectList(qw);
    }

    /** 按 ID 读取工作项，不存在时抛 404 风格异常，避免上层 NPE。 */
    public WorkItem get(Long id) {
        WorkItem item = mapper.selectById(id);
        if (item == null) {
            throw new BusinessException(4040, "工作项不存在");
        }
        return item;
    }

    /** 读取状态历史（按时间升序），供追溯和复盘页面直接展示。 */
    public List<WorkItemStatusLog> statusHistory(Long id) {
        return statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                .eq("work_item_id", id).orderByAsc("at"));
    }

    /**
     * 查询当前状态的下一个可达状态集合（只读）：
     * <ul>
     *   <li>先读工作项，确保目标实例存在；不存在时抛 404。</li>
     *   <li>基于类型+当前状态交给状态机计算，保持与真实流转口径一致。</li>
     * </ul>
     *
     * @param id 工作项 ID
     * @return 可选目标状态集合（有序性不保证）
     * @throws BusinessException 工作项不存在时抛 4040
     */
    public Set<String> nextStatuses(Long id) {
        WorkItem item = get(id);
        return StateMachine.nextStatuses(WorkItemType.of(item.getType()), item.getStatus());
    }

    /**
     * 创建工作项（无父子关系）：
     * <ul>
     *   <li>直接复用 {@link #create(WorkItem, Long)} 统一入口，确保校验与审计一致。</li>
     *   <li>不传 parentId，避免写入追溯关系表。</li>
     * </ul>
     *
     * @param item 工作项草稿
     * @return 持久化后的工作项
     */
    @Transactional
    public WorkItem create(WorkItem item) {
        return create(item, null);
    }

    /**
     * 创建工作项 + 可选建立树关系（更新粒度注释）：
     * <ol>
     *   <li>校验所属项目存在；构造业务主键 code，默认状态、创建/更新时间与操作者。</li>
     *   <li>持久化工作项主表记录。</li>
     *   <li>写入状态日志（from=null, to=initial），形成可回放状态链。</li>
     *   <li>记录 CREATE 审计动作，保留变更前后镜像。</li>
     *   <li>若 parentId 非空则创建一条 <code>parent_of</code> 追溯边。</li>
     * </ol>
     * <p>失败策略：方法标记事务边界，任一写入失败会触发回滚，避免出现“主记录写入成功但日志或关系缺失”的不一致。</p>
     *
     * @param item 工作项草稿；type 为空会在类型解析阶段失败
     * @param parentId 可选父节点 ID；用于补齐父子关系
     * @return 持久化成功且已完成副作用闭环的工作项
     * @throws BusinessException 项目不存在、类型非法、数据库约束失败
     */
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

    /**
     * 构建需求树（纯计算）：
     * <ul>
     *   <li>按类型加载能力/需求/故事/任务，按主键顺序稳定输出。</li>
     *   <li>读取 parent_of 关系并生成父子映射，仅保留关系两端都在集合内的条目。</li>
     *   <li>以“无 parent 的节点”为根向下构建，并对重复访问做保护。</li>
     *   <li>循环依赖会被 visited 过滤，不会导致递归无限循环。</li>
     * </ul>
     * <p>这是纯读方法，不做任何持久化变更。</p>
     *
     * @param projectId 项目 ID
     * @return 树根列表
     */
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

    /**
     * 递归构建树节点（含防环）：
     * 使用 <code>visited</code> 记录已访问路径，命中重复 id 时剪枝返回，避免异常数据导致死循环。
     * 入参 map 集合为内存视图，未命中 parent-of 边即返回空 children，确保树构建不会因脏数据失败。
     */
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

    /**
     * 更新工作项（update 粒度）：
     * <ol>
     *   <li>读取旧值并生成最小快照用于审计对比；</li>
     *   <li>仅按非空 patch 做局部覆盖，避免空字段覆盖历史有效值；</li>
     *   <li>补齐更新人和更新时间；</li>
     *   <li>写审计时按 owner 变更与通用更新拆分动作类型。</li>
     * </ol>
     * <p>失败策略：主记录不存在或数据库更新失败将抛异常；事务内失败会回滚写入，保持读写一致。</p>
     *
     * @param id 工作项 ID
     * @param patch 更新载荷（只允许白名单字段的增量写入）
     * @return 更新后的工作项实体
     * @throws BusinessException 工作项不存在时抛 4040
     */
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
     * 状态流转预检（dry-run）：
     * <ul>
     *   <li>加载当前实体并复用真实转移入口的同一校验逻辑（守卫、回退规则）。</li>
     *   <li>不做数据库更新，仅返回是否可达性与失败原因。</li>
     *   <li>支持批量前置校验：调用方可先聚合所有失败原因再统一反馈。</li>
     * </ul>
     *
     * @param id 工作项 ID
     * @param toStatus 目标状态
     * @param reason 回退/异动理由（回退场景必填）
     */
    public void preflight(Long id, String toStatus, String reason) {
        WorkItem item = get(id);
        // 与真实流转同口径：进入 Accepted 前引擎会补齐验收人/时间（此处仅内存，不落库）
        if ("Accepted".equals(toStatus)) {
            item.setAcceptedBy(UserContext.currentUserId());
            item.setAcceptedAt(LocalDateTime.now());
        }
        validateTransition(item, toStatus, reason);
    }

    /**
     * 状态校验流水线（只读）：
     * <ul>
     *   <li>校验目标状态与当前状态是否有实际变化。</li>
     *   <li>根据状态机判断转移合法性并返回明确错误码/文案。</li>
     *   <li>识别回退路径并强制提供原因。</li>
     *   <li>逐个执行可插拔的 <code>TransitionGuard</code>，支持不同角色/场景复用。</li>
     * </ul>
     * <p><code>preflight</code> 与 <code>transition</code> 共用本方法，避免“预检通过但执行失败”的规则歧义。</p>
     *
     * @param item 待流转实体
     * @param toStatus 目标状态
     * @param reason 理由（回退场景必填）
     * @throws BusinessException 转移非法、缺失原因、守卫不通过
     */
    private void validateTransition(WorkItem item, String toStatus, String reason) {
        WorkItemType type = WorkItemType.of(item.getType());
        String from = item.getStatus();

        if (from.equals(toStatus)) {
            throw new BusinessException("目标状态与当前状态相同");
        }
        if (!StateMachine.canTransition(type, from, toStatus)) {
            throw new BusinessException(4091,
                    "不允许的状态流转：" + Labels.status(from, item.getType())
                            + " → " + Labels.status(toStatus, item.getType())
                            + "（类型 " + type.label() + "）");
        }
        boolean backward = StateMachine.isBackward(type, from, toStatus);
        if (backward && (reason == null || reason.isBlank())) {
            throw new BusinessException("状态回退必须填写理由");
        }

        TransitionContext ctx = new TransitionContext().setReason(reason);
        for (TransitionGuard guard : guards) {
            if (guard.supports(item, toStatus)) {
                guard.check(item, toStatus, ctx);
            }
        }
    }

    /**
     * 状态流转主流程（写链路）：
     * <ol>
     *   <li>加载实体，若流向 Accepted 补齐验收人及验收时间，确保守卫观察上下文一致。</li>
     *   <li>调用 {@link #validateTransition(WorkItem, String, String)} 统一规则判定。</li>
     *   <li>写回新状态与元数据（更新人/更新时间）。</li>
     *   <li>新增状态日志，保存 from/to 及原因。</li>
     *   <li>记录 STATUS_CHANGE 审计。</li>
     * </ol>
     * <p>失败策略：方法有事务边界，任一步骤异常触发回滚，避免状态与日志不一致。</p>
     *
     * @param id 工作项 ID
     * @param toStatus 目标状态
     * @param reason 变更原因
     * @return 变更后的工作项
     * @throws BusinessException 未通过规则、工作项不存在或数据库更新失败
     */
    @Transactional
    public WorkItem transition(Long id, String toStatus, String reason) {
        WorkItem item = get(id);
        String from = item.getStatus();
        Long uid = UserContext.currentUserId();
        // 进入 Accepted：先补齐验收人/时间，再过守卫（守卫可能校验验收人）
        if ("Accepted".equals(toStatus)) {
            item.setAcceptedBy(uid);
            item.setAcceptedAt(LocalDateTime.now());
        }
        validateTransition(item, toStatus, reason);

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
                item.getCode() + " 状态 " + Labels.status(from, item.getType())
                        + " → " + Labels.status(toStatus, item.getType())
                        + (reason != null ? "（" + reason + "）" : ""),
                Set.of("status", from), Set.of("status", toStatus));
        return item;
    }

    /**
     * 审计最小快照复制。
     * 仅保留少量关键字段以降低审计差异成本并提升可读性，避免日志中携带大体积或敏感字段。
     */
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
