package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.IterationMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.statemachine.GuardException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 迭代服务（T205/T206）: 提供迭代主数据维护、承诺分配与复盘统计。
 * 关键规则：工作项进迭代前需满足 Ready 检查；承诺记录只增不删，复盘以承诺快照为事实口径。
 */
@Service
public class IterationService {

    private final IterationMapper mapper;
    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final com.ipd.toolbox.mapper.IterationCommitmentMapper commitmentMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    /**
     * 迭代服务依赖注入。
     * mapper 负责迭代主数据；workItemMapper 负责工作项归属与承诺快照读取；
     * commitmentMapper 管理“仅增不删”的迭代承诺记录；audit 负责变更留痕。
     */
    public IterationService(IterationMapper mapper, ProjectMapper projectMapper,
                            WorkItemMapper workItemMapper,
                            com.ipd.toolbox.mapper.IterationCommitmentMapper commitmentMapper,
                            CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.commitmentMapper = commitmentMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    /**
     * 列出项目下的迭代清单（只读）。
     *
     * <p>按开始时间倒序排序；如开始日期为空，默认退回主键序保证稳定输出。
     * 不预过滤 hidden，展示控制在上游完成。</p>
     */
    public List<Iteration> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", projectId).orderByDesc("start_date").orderByDesc("id"));
    }

    /**
     * 创建迭代（写链路）：
     * <ol>
     *   <li>校验所属项目存在。</li>
     *   <li>生成 code、补齐默认状态、创建人、更新时间戳。</li>
     *   <li>入库主数据并记录 CREATE 审计。</li>
     * </ol>
     * <p>更新粒度：新增一条迭代记录 + 一条审计事件；不触及工作项承诺关系。</p>
     * <p>失败策略：项目不存在或 insert/审计失败抛异常，事务回滚。</p>
     *
     * @param it 迭代草稿
     * @return 持久化迭代实体
     */
    @Transactional
    public Iteration create(Iteration it) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(it.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        Long uid = UserContext.currentUserId();
        it.setId(null);
        it.setCode(codeGenerator.next(project.getId(), project.getCode(), "SPR"));
        if (it.getStatus() == null) it.setStatus("PLANNING");
        it.setCreatedBy(uid);
        it.setUpdatedBy(uid);
        it.setCreatedAt(LocalDateTime.now());
        it.setUpdatedAt(LocalDateTime.now());
        it.setDeleted(0);
        mapper.insert(it);
        audit.record(it.getProjectId(), "ITERATION", it.getId(), "CREATE",
                "创建迭代 " + it.getCode() + " " + it.getName(), null, it);
        return it;
    }

    /**
     * 更新迭代（写链路）：
     * <ol>
     *   <li>按主键读取旧值，若不存在抛 4040。</li>
     *   <li>按补丁非空字段更新，不会清空既有值。</li>
     *   <li>比较 hidden 字段变化以便审计文案区分“隐藏/取消隐藏”。</li>
     *   <li>更新主表并记录 UPDATE 行为。</li>
     * </ol>
     * <p>更新粒度：仅变更迭代属性与可见性；不直接影响承诺历史。</p>
     * <p>失败策略：不存在或非法输入时直接抛异常，事务性写入回滚。</p>
     */
    @Transactional
    public Iteration update(Long id, Iteration patch) {
        UserContext.requireRole("PM");
        Iteration old = mapper.selectById(id);
        if (old == null) {
            throw new BusinessException(4040, "迭代不存在");
        }
        if (patch.getName() != null) old.setName(patch.getName());
        if (patch.getGoal() != null) old.setGoal(patch.getGoal());
        if (patch.getStartDate() != null) old.setStartDate(patch.getStartDate());
        if (patch.getEndDate() != null) old.setEndDate(patch.getEndDate());
        if (patch.getStatus() != null) old.setStatus(patch.getStatus());
        boolean hiddenChanged = patch.getHidden() != null && !patch.getHidden().equals(old.getHidden());
        if (patch.getHidden() != null) old.setHidden(patch.getHidden());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        String detail = hiddenChanged
                ? (old.getHidden() != null && old.getHidden() == 1 ? "隐藏迭代 " : "取消隐藏迭代 ") + old.getCode()
                : "更新迭代 " + old.getCode();
        audit.record(old.getProjectId(), "ITERATION", id, "UPDATE", detail, null, old);
        return old;
    }

    /**
     * 查询某迭代下的工作项（只读）：
     * <p>按 id 升序返回承诺与看板顺序一致的视图；
     * 仅反映数据库当前归属关系，不补偿脏数据。</p>
     */
    public List<WorkItem> items(Long iterationId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("iteration_id", iterationId).orderByAsc("id"));
    }

    /** 守卫与复盘逻辑分区注释。 */
    @Transactional
    // ---------- 迭代复盘 ----------

    public record RetroItem(Long id, String code, String type, String title, String status,
                            String estimateSnap, boolean done, boolean movedOut) {
    }

    public record VelocityPoint(Long iterationId, String code, String name,
                                java.time.LocalDate endDate, long committed, long done) {
    }

    public record Retro(Iteration iteration, List<RetroItem> items,
                        long committedCount, long doneCount, long spilloverCount, long movedOutCount,
                        int completionRate, List<VelocityPoint> velocity) {
    }

    private static final Set<String> DONE_STATUSES = Set.of("Accepted", "Closed", "Verified");

    /**
     * 迭代复盘（读模型）：
     * <ul>
     *   <li>以承诺快照作为事实口径，计算完成率、移出率、溢出率。</li>
     *   <li>移出定义为该项被移出当前迭代但仍有状态变化。</li>
     *   <li>趋势使用同项目历史迭代的承诺/完成对照，空快照迭代会被剔除。</li>
     * </ul>
     * <p>不落库，仅聚合返回，便于复盘页面直接渲染。</p>
     */
    public Retro retro(Long iterationId) {
        Iteration it = mapper.selectById(iterationId);
        if (it == null) {
            throw new BusinessException(4040, "迭代不存在");
        }
        List<RetroItem> items = retroItems(iterationId);
        long done = items.stream().filter(RetroItem::done).count();
        long movedOut = items.stream().filter(r -> r.movedOut() && !r.done()).count();
        long spillover = items.size() - done - movedOut;
        int rate = items.isEmpty() ? 0 : (int) Math.round(done * 100.0 / items.size());

        List<VelocityPoint> velocity = new ArrayList<>();
        for (Iteration past : mapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", it.getProjectId()).isNotNull("end_date").orderByAsc("end_date"))) {
            List<RetroItem> pastItems = retroItems(past.getId());
            if (pastItems.isEmpty() && !past.getId().equals(iterationId)) {
                continue; // 无承诺的迭代不进趋势
            }
            velocity.add(new VelocityPoint(past.getId(), past.getCode(), past.getName(), past.getEndDate(),
                    pastItems.size(), pastItems.stream().filter(RetroItem::done).count()));
        }
        return new Retro(it, items, items.size(), done, spillover, movedOut, rate, velocity);
    }

    /**
     * 读取单个迭代的复盘条目（私有读）：
     * <p>使用快照表中的承诺记录生成 RetroItem，若工作项已被清理则跳过，
     * 从而保证复盘输出不因为历史垃圾数据失败。</p>
     */
    private List<RetroItem> retroItems(Long iterationId) {
        List<RetroItem> out = new ArrayList<>();
        for (com.ipd.toolbox.domain.entity.IterationCommitment c : commitmentMapper.selectList(
                new QueryWrapper<com.ipd.toolbox.domain.entity.IterationCommitment>()
                        .eq("iteration_id", iterationId).orderByAsc("id"))) {
            WorkItem w = workItemMapper.selectById(c.getWorkItemId());
            if (w == null) {
                continue;
            }
            boolean done = DONE_STATUSES.contains(w.getStatus());
            boolean movedOut = !iterationId.equals(w.getIterationId());
            out.add(new RetroItem(w.getId(), w.getCode(), w.getType(), w.getTitle(), w.getStatus(),
                    c.getEstimateSnap(), done, movedOut));
        }
        return out;
    }

    /**
     * 进迭代预检（dry-run）：
     * <ul>
     *   <li>复用 {@link #checkAssignable(Long, Long)} 的全部判定逻辑。</li>
     *   <li>不落库，供批量分派前提前拦截。</li>
     *   <li>预检通过并不执行任何写操作。</li>
     * </ul>
     */
    public void preflightAssign(Long iterationId, Long workItemId) {
        checkAssignable(iterationId, workItemId);
    }

    /**
     * 进迭代前置校验（只读）：
     * <ul>
     *   <li>校验迭代与工作项均存在。</li>
     *   <li>检查 Ready 条件：验收标准、责任人、估算均必须填写。</li>
     *   <li>通过后返回待分配工作项实体，供 assign 使用，避免重复查询。</li>
     * </ul>
     *
     * @return 可分配的工作项（尚未更新迭代）
     * @throws BusinessException 迭代或工作项不存在
     * @throws GuardException Ready 条件不满足时抛出
     */
    private WorkItem checkAssignable(Long iterationId, Long workItemId) {
        Iteration it = mapper.selectById(iterationId);
        if (it == null) {
            throw new BusinessException(4040, "迭代不存在");
        }
        WorkItem wi = workItemMapper.selectById(workItemId);
        if (wi == null) {
            throw new BusinessException(4040, "工作项不存在");
        }
        if (isBlank(wi.getAcceptanceCriteria()) || wi.getOwnerId() == null || isBlank(wi.getEstimate())) {
            throw new GuardException("GUARD_READY_UNMET",
                    "进入 Sprint 承诺前需满足 Ready 条件（验收条件、责任人、估算）");
        }
        return wi;
    }

    /**
     * 将工作项纳入迭代（写链路）：
     * <ol>
     *   <li>执行进迭代前置校验（守卫 + existence）。</li>
     *   <li>更新工作项 <code>iteration_id</code> 与更新时间。</li>
     *   <li>写入承诺快照（commitment），快照仅新增不删除，已存在则幂等跳过。</li>
     *   <li>记录 WORK_ITEM UPDATE 审计。</li>
     * </ol>
     * <p>更新粒度：主工作项归属变更 + 承诺快照新增（如不存在）。
     * 并发重复调用下，快照写入是幂等保护。
     * </p>
     */
    public void assign(Long iterationId, Long workItemId) {
        WorkItem wi = checkAssignable(iterationId, workItemId);
        Iteration it = mapper.selectById(iterationId); // 预检已保证存在

        wi.setIterationId(iterationId);
        wi.setUpdatedBy(UserContext.currentUserId());
        wi.setUpdatedAt(LocalDateTime.now());
        workItemMapper.updateById(wi);
        // 承诺快照：拉入即承诺（只增不删），承诺完成率以此为分母
        Long existing = commitmentMapper.selectCount(
                new QueryWrapper<com.ipd.toolbox.domain.entity.IterationCommitment>()
                        .eq("iteration_id", iterationId).eq("work_item_id", workItemId));
        if (existing == null || existing == 0) {
            com.ipd.toolbox.domain.entity.IterationCommitment c =
                    new com.ipd.toolbox.domain.entity.IterationCommitment();
            c.setIterationId(iterationId);
            c.setWorkItemId(workItemId);
            c.setEstimateSnap(wi.getEstimate());
            c.setCommittedAt(LocalDateTime.now());
            commitmentMapper.insert(c);
        }
        audit.record(wi.getProjectId(), "WORK_ITEM", workItemId, "UPDATE",
                wi.getCode() + " 拉入迭代 " + it.getCode(), null, null);
    }

    /**
     * 将工作项移出迭代（写链路）：
     * <ul>
     *   <li>使用 UpdateWrapper 强制 <code>iteration_id=null</code>，避免 null 更新被 MyBatis-Plus 忽略。</li>
     *   <li>更新操作者与更新时间，确保后续补偿可追踪。</li>
     *   <li>记录 WORK_ITEM UPDATE 审计。</li>
     * </ul>
     * <p>失败策略：不存在则无副作用直接返回；更新失败抛异常。</p>
     */
    @Transactional
    public void remove(Long workItemId) {
        WorkItem wi = workItemMapper.selectById(workItemId);
        if (wi == null) {
            return;
        }
        // null 字段经 updateById 会被忽略，改用 UpdateWrapper 强制置空 iteration_id
        workItemMapper.update(null, new UpdateWrapper<WorkItem>()
                .eq("id", workItemId)
                .set("iteration_id", null)
                .set("updated_by", UserContext.currentUserId())
                .set("updated_at", LocalDateTime.now()));
        audit.record(wi.getProjectId(), "WORK_ITEM", workItemId, "UPDATE",
                wi.getCode() + " 移出迭代", null, null);
    }

    /**
     * 空白字符串判定（工具方法）：
     * 给前置检查提供统一空值语义，避免散落到业务主路径中造成判断口径不一致。
     */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
