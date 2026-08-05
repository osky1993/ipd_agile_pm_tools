package com.ipd.toolbox.service;

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

    public BaselineService(BaselineMapper mapper, BaselineItemMapper itemMapper,
                           ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                           AuditService audit) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.audit = audit;
    }

    public List<Baseline> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Baseline>()
                .eq("project_id", projectId).orderByDesc("id"));
    }

    public Baseline get(Long id) {
        Baseline b = mapper.selectById(id);
        if (b == null) {
            throw new BusinessException(4040, "基线不存在");
        }
        return b;
    }

    public List<BaselineItem> items(Long baselineId) {
        return itemMapper.selectList(new QueryWrapper<BaselineItem>()
                .eq("baseline_id", baselineId).orderByAsc("id"));
    }

    /** 建立基线：冻结当前需求域清单。手动来源需 PM 角色；DCP 来源由评审事务内调用。 */
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
                "建立基线 " + b.getName() + "（" + scope.size() + " 项，来源 " + source + "）", null, null);
        return b;
    }

    public Diff diff(Long baselineId) {
        Baseline b = get(baselineId);
        Map<Long, WorkItem> current = new HashMap<>();
        for (WorkItem w : scopeItems(b.getProjectId())) {
            current.put(w.getId(), w);
        }
        return diff(b, items(baselineId), current);
    }

    /** diff 纯函数：以 work_item_id 对齐冻结明细与当前需求域清单。 */
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

    private List<WorkItem> scopeItems(Long projectId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).in("type", SCOPE_TYPES));
    }
}
