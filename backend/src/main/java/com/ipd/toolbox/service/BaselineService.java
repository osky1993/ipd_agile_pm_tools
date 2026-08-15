package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Baseline;
import com.ipd.toolbox.domain.entity.BaselineItem;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.BaselineItemMapper;
import com.ipd.toolbox.mapper.BaselineMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 范围基线：把"某时点承诺了什么"（需求域三类工作项）冻结为可对比的快照。
 * 只增不删（与 decision 同哲学）；对比当前呈现范围蔓延/日期偏差/估算漂移。
 * IPD 语义：DCP 通过即自动固化（承诺时刻），此后变更管理有了量化的对比基准。
 */
@Service
public class BaselineService {

    /** 需求域：进入范围基线的工作项类型（执行细节 TASK/DEFECT 不入基线） */
    static final Set<String> SCOPE_TYPES = Set.of("CAPABILITY", "REQUIREMENT", "STORY");
    private static final Set<String> DONE_STATUSES = Set.of("Accepted", "Closed");

    public record DiffRow(Long workItemId, String code, String title, String type,
                          String kind /* ADDED|REMOVED|DONE|OPEN */,
                          String baselineStatus, String currentStatus,
                          LocalDate plannedDate, LocalDate forecastDate, Long slipDays,
                          String baselineEstimate, String currentEstimate, Double estimateDelta) {
    }

    public record DiffSummary(int baselineCount, int currentCount, int added, int removed, int done,
                              double creepRate, Double avgSlipDays, Long maxSlipDays,
                              double estimateDeltaTotal) {
    }

    public record Diff(Baseline baseline, DiffSummary summary, List<DiffRow> rows) {
    }

    private final BaselineMapper mapper;
    private final BaselineItemMapper itemMapper;
    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final AuditService audit;

    /**
     * 范围基线服务依赖注入。
     * scopeItems 基于 workItemMapper 查询基线作用域条目；
     * 项目存在性依赖 projectMapper；diff 与变更通过 mapper/itemMapper 落库。
     * audit 负责 create 审计留痕。
     */
    public BaselineService(BaselineMapper mapper, BaselineItemMapper itemMapper,
                           ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                           AuditService audit) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.audit = audit;
    }

    /**
     * 按项目列出基线。
     *
     * <p>读取语义：
     * <ul>
     *   <li>按 `project_id` 过滤，按 `id` 降序返回。</li>
     *   <li>仅返回基线主表，不展开明细项。</li>
     * </ul>
     * 无副作用；前端可基于返回的 `id` 再调用 `items/diff` 逐条展开。
     */
    public List<Baseline> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Baseline>()
                .eq("project_id", projectId).orderByDesc("id"));
    }

    /**
     * 获取基线主记录。
     *
     * <p>边界：不存在直接抛出 `BusinessException(4040, ...)`，用于统一上层错误码映射。</p>
     *
     * @param id 基线 ID
     * @return 基线实体
     */
    public Baseline get(Long id) {
        Baseline b = mapper.selectById(id);
        if (b == null) {
            throw new BusinessException(4040, "基线不存在");
        }
        return b;
    }

    /**
     * 列出基线快照行。
     *
     * <p>纯读方法：返回该基线捕获时点的快照明细，不回读当前工作项状态。</p>
     * <p>返回顺序稳定按 `id` 升序，便于 UI 做增量滚动与回归比对。</p>
     */
    public List<BaselineItem> items(Long baselineId) {
        return itemMapper.selectList(new QueryWrapper<BaselineItem>()
                .eq("baseline_id", baselineId).orderByAsc("id"));
    }

    /**
     * 建立基线并冻结当前需求域快照。
     *
     * <p>用途与更新粒度：</p>
     * <ul>
     *   <li>先验校验：PROJECT 存在性、来源权限（`MANUAL` 场景要求 PM）；`name` 可空。</li>
     *   <li>主表创建：插入 BASELINE 头（含来源、统计信息与创建人）。</li>
     *   <li>明细快照：按 `scopeItems` 计算 CAPABILITY/REQUIREMENT/STORY 当前集合，逐条写入 BASELINE_ITEM。</li>
     *   <li>每个基线项只复制当前字段，不持久化回源对象，属于纯快照行为。</li>
     *   <li>审计副作用：记录 `BASELINE CREATE`。</li>
     * </ul>
     * <p>失败策略：方法标注事务；任一校验失败或入库异常导致整体回滚。已生成的序号、名称不外溢到其他资源。</p>
     * <p>幂等说明：名称为空时按项目下已有基线数量 +1 计算补位，不对外提供幂等 token；重复调用会生成独立基线。</p>
     * <p>边界：来源 DCP（评审调用）建议由上游填入 `source=MANUAL/ DCP` 或 `stageGateId/decisionId`，方法本身不做来源合法性白名单。</p>
     *
     * <p>MANUAL 来源需要 PM 角色；DCP 来源由评审流程内直接调用。</p>
     * <p>快照按当前需求域条目（类型 CAP/REQ/ STORY）落库，不回写原对象。</p>
     */
    @Transactional
    public Baseline create(Long projectId, String name, String source, Long stageGateId, Long decisionId) {
        if ("MANUAL".equals(source)) {
            UserContext.requireRole("PM");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        List<WorkItem> scope = scopeItems(projectId);

        Baseline b = new Baseline();
        b.setProjectId(projectId);
        b.setName(name == null || name.isBlank() ? "B" + (mapper.selectCount(
                new QueryWrapper<Baseline>().eq("project_id", projectId)) + 1) : name.trim());
        b.setSource(source);
        b.setStageGateId(stageGateId);
        b.setDecisionId(decisionId);
        b.setItemCount(scope.size());
        b.setCreatedBy(UserContext.currentUserId());
        b.setCreatedAt(LocalDateTime.now());
        mapper.insert(b);

        for (WorkItem w : scope) {
            BaselineItem item = new BaselineItem();
            item.setBaselineId(b.getId());
            item.setWorkItemId(w.getId());
            item.setCode(w.getCode());
            item.setTitle(w.getTitle());
            item.setType(w.getType());
            item.setStatus(w.getStatus());
            item.setEstimate(w.getEstimate());
            item.setPlannedDate(w.getForecastDate());
            itemMapper.insert(item);
        }
        audit.record(projectId, "BASELINE", b.getId(), "CREATE",
                "建立基线 " + b.getName() + "（" + scope.size() + " 项，来源 "
                        + ("DCP".equals(source) ? "DCP 固化" : "手动") + "）", null, null);
        return b;
    }

    /**
     * 对比快照与当前需求域并返回变更矩阵。
     *
     * <p>边界：不更改任何持久化状态，完全基于一份冻结明细 + 当前需求域快照做比对。</p>
     * <p>返回总量：行级明细包含 ADDED/REMOVED/DONE/OPEN 四类，便于前端分别渲染趋势与列表。</p>
     */
    public Diff diff(Long baselineId) {
        Baseline b = get(baselineId);
        Map<Long, WorkItem> current = new HashMap<>();
        for (WorkItem w : scopeItems(b.getProjectId())) {
            current.put(w.getId(), w);
        }
        return diff(b, items(baselineId), current);
    }

    /**
     * diff 纯函数：以 work_item_id 对齐冻结明细与当前需求域清单。
     *
     * <p>按 ADDED/REMOVED/DONE/OPEN 四类输出行，并计算范围蔓延率、平均延期、总估算漂移。</p>
     * <p>聚合规则：</p>
     * <ul>
     *   <li>冻结项在当前不存在 => `REMOVED`。</li>
     *   <li>冻结项在当前存在且状态在 `Accepted/Closed` => `DONE`，否则 `OPEN`。</li>
     *   <li>当前存在但冻结不存在 => `ADDED`。</li>
     * </ul>
     * <p>计算边界：</p>
     * <ul>
     *   <li>延期天数以冻结 `plannedDate` 与当前 `forecastDate` 差值为准，非空时计数。</li>
     *   <li>估算差值 `ce - be`；当两侧估算均为空/0 时返回 null，但仍计入行。</li>
     *   <li>`scope` 计数基于入参 `frozen` 大小，避免并发期间外部变化影响 creepRate 的数学口径。</li>
     * </ul>
     *
     * @param b 基线头
     * @param frozen 基线明细
     * @param current 当前需求域映射
     * @return 差异汇总 + 逐项变化
     */
    static Diff diff(Baseline b, List<BaselineItem> frozen, Map<Long, WorkItem> current) {
        List<DiffRow> rows = new ArrayList<>();
        Set<Long> frozenIds = new java.util.HashSet<>();
        int done = 0;
        long slipSum = 0;
        int slipCount = 0;
        Long maxSlip = null;
        double estimateDeltaTotal = 0;

        for (BaselineItem f : frozen) {
            frozenIds.add(f.getWorkItemId());
            WorkItem cur = current.get(f.getWorkItemId());
            if (cur == null) {
                // 已删除或类型改出需求域 → 视为范围移除
                rows.add(new DiffRow(f.getWorkItemId(), f.getCode(), f.getTitle(), f.getType(),
                        "REMOVED", f.getStatus(), null, f.getPlannedDate(), null, null,
                        f.getEstimate(), null, null));
                continue;
            }
            boolean isDone = DONE_STATUSES.contains(cur.getStatus());
            Long slip = null;
            if (f.getPlannedDate() != null && cur.getForecastDate() != null) {
                slip = ChronoUnit.DAYS.between(f.getPlannedDate(), cur.getForecastDate());
                slipSum += slip;
                slipCount++;
                if (maxSlip == null || slip > maxSlip) {
                    maxSlip = slip;
                }
            }
            Double estDelta = null;
            double be = PerfService.parsePoints(f.getEstimate());
            double ce = PerfService.parsePoints(cur.getEstimate());
            if (be > 0 || ce > 0) {
                estDelta = ce - be;
                estimateDeltaTotal += estDelta;
            }
            if (isDone) {
                done++;
            }
            rows.add(new DiffRow(f.getWorkItemId(), f.getCode(), cur.getTitle(), f.getType(),
                    isDone ? "DONE" : "OPEN", f.getStatus(), cur.getStatus(),
                    f.getPlannedDate(), cur.getForecastDate(), slip,
                    f.getEstimate(), cur.getEstimate(), estDelta));
        }
        int added = 0;
        for (WorkItem w : current.values()) {
            if (!frozenIds.contains(w.getId())) {
                added++;
                rows.add(new DiffRow(w.getId(), w.getCode(), w.getTitle(), w.getType(),
                        "ADDED", null, w.getStatus(), null, w.getForecastDate(), null,
                        null, w.getEstimate(), null));
            }
        }
        int removed = (int) rows.stream().filter(r -> "REMOVED".equals(r.kind())).count();
        double creepRate = frozen.isEmpty() ? 0
                : Math.round(added * 1000.0 / frozen.size()) / 10.0;
        Double avgSlip = slipCount == 0 ? null
                : Math.round(slipSum * 10.0 / slipCount) / 10.0;
        DiffSummary summary = new DiffSummary(frozen.size(), current.size(), added, removed, done,
                creepRate, avgSlip, maxSlip, Math.round(estimateDeltaTotal * 10.0) / 10.0);
        return new Diff(b, summary, rows);
    }

    /**
     * 读取项目范围内基线口径工作项（CAPABILITY/REQUIREMENT/STORY）。
     *
     * <p>范围边界说明：仅在数据库层按 `type` in scope 读取，状态不再额外过滤。
     * 该方法是 `create/diff` 的共享语义入口，确保两者口径一致。</p>
     */
    private List<WorkItem> scopeItems(Long projectId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).in("type", SCOPE_TYPES));
    }
}
