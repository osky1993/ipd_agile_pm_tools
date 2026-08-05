package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Baseline;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Evidence;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.BaselineMapper;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.EvidenceMapper;
import com.ipd.toolbox.mapper.IterationMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.mapper.WorkItemStatusLogMapper;
import com.ipd.toolbox.statemachine.StateMachine;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时光机（复盘回溯）：把项目状态重建到任意历史时点。
 * 核心是纯回放——状态时间线（work_item_status_log）自第一版起完整记录且无旁路写入，
 * 状态与存在性维度可精确重建；字段值历史与追溯链时点不在 V1 承诺范围。
 * 已逻辑删除的项也参与回放（绕过 @TableLogic），否则历史失真。
 */
@Service
public class TimeMachineService {

    /** 事件轨道标记：DECISION / BASELINE / ITER_START / ITER_END */
    public record EventPoint(LocalDate date, String kind, String label, Long refId) {
    }

    public record Timeline(LocalDate start, LocalDate end, List<EventPoint> events) {
    }

    public record AsOfItem(Long id, String code, String type, String title,
                           String statusAtDate, boolean deletedNow) {
    }

    public record DayEvent(String kind, String text) {
    }

    public record AsOf(LocalDate date,
                       int reqTotal, int reqAccepted, int defectsOpen, int wip,
                       int risksOpen, int decisionCount, int evidenceCount,
                       Map<String, Map<String, Integer>> byTypeStatus,
                       List<AsOfItem> items, List<DayEvent> dayEvents) {
    }

    private static final Set<String> WIP_STATUSES =
            Set.of("In Progress", "Analysing", "Fixing", "Mitigating", "Retesting");
    /** 终态集合（对比时"完成"的判定口径） */
    private static final Set<String> DONE_STATUSES =
            Set.of("Accepted", "Closed", "Verified", "Implemented");

    // ---------- A/B 双时点对比（V2） ----------

    public record CompareRow(Long id, String code, String type, String title,
                             String statusFrom, String statusTo,
                             String kind /* NEW | COMPLETED | CHANGED | UNCHANGED */) {
    }

    public record Kpis(int reqTotal, int reqAccepted, int defectsOpen, int wip, int risksOpen) {
    }

    public record Compare(LocalDate from, LocalDate to, Kpis kpisFrom, Kpis kpisTo,
                          int added, int completed, int changed, int unchanged,
                          int transitionCount, List<CompareRow> rows,
                          List<DayEvent> periodEvents) {
    }

    /** A→B 期间发生了什么：两次回放 diff + 期间决策/基线/流转统计。 */
    public Compare compare(Long projectId, LocalDate from, LocalDate to) {
        requireProject(projectId);
        if (from.isAfter(to)) {
            LocalDate t = from;
            from = to;
            to = t;
        }
        List<WorkItem> allItems = workItemMapper.selectAllIncludingDeleted(projectId);
        List<WorkItemStatusLog> logs = allItems.isEmpty() ? List.of()
                : statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                        .in("work_item_id", allItems.stream().map(WorkItem::getId).toList())
                        .orderByAsc("id"));
        Map<Long, String> atFrom = replay(allItems, logs, from);
        Map<Long, String> atTo = replay(allItems, logs, to);

        List<CompareRow> rows = new ArrayList<>();
        int added = 0;
        int completed = 0;
        int changed = 0;
        int unchanged = 0;
        for (WorkItem w : allItems) {
            String sTo = atTo.get(w.getId());
            if (sTo == null) {
                continue; // B 时点尚不存在
            }
            String sFrom = atFrom.get(w.getId());
            String kind;
            if (sFrom == null) {
                kind = DONE_STATUSES.contains(sTo) ? "COMPLETED" : "NEW"; // 期间新增且已完成的计入完成
                if ("NEW".equals(kind)) {
                    added++;
                } else {
                    added++;
                    completed++;
                }
            } else if (sFrom.equals(sTo)) {
                kind = "UNCHANGED";
                unchanged++;
            } else if (DONE_STATUSES.contains(sTo) && !DONE_STATUSES.contains(sFrom)) {
                kind = "COMPLETED";
                completed++;
            } else {
                kind = "CHANGED";
                changed++;
            }
            if (!"UNCHANGED".equals(kind)) {
                rows.add(new CompareRow(w.getId(), w.getCode(), w.getType(), w.getTitle(),
                        sFrom, sTo, kind));
            }
        }
        // 期间流转次数与大事（决策/基线，(from, to] 窗口）
        LocalDateTime fromEnd = from.plusDays(1).atStartOfDay();
        LocalDateTime toEnd = to.plusDays(1).atStartOfDay();
        int transitionCount = 0;
        for (WorkItemStatusLog log : logs) {
            if (log.getAt() != null && !log.getAt().isBefore(fromEnd) && log.getAt().isBefore(toEnd)) {
                transitionCount++;
            }
        }
        List<DayEvent> periodEvents = new ArrayList<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).orderByAsc("id"))) {
            if (d.getDecidedAt() != null && !d.getDecidedAt().isBefore(fromEnd)
                    && d.getDecidedAt().isBefore(toEnd)) {
                periodEvents.add(new DayEvent("DECISION",
                        d.getDecidedAt().toLocalDate() + " 决策 " + d.getCode() + " 结论 " + d.getConclusion()));
            }
        }
        for (Baseline b : baselineMapper.selectList(new QueryWrapper<Baseline>()
                .eq("project_id", projectId).orderByAsc("id"))) {
            if (!b.getCreatedAt().isBefore(fromEnd) && b.getCreatedAt().isBefore(toEnd)) {
                periodEvents.add(new DayEvent("BASELINE",
                        b.getCreatedAt().toLocalDate() + " 建立基线 " + b.getName()));
            }
        }
        return new Compare(from, to, kpis(allItems, atFrom), kpis(allItems, atTo),
                added, completed, changed, unchanged, transitionCount, rows, periodEvents);
    }

    /** 时点 KPI 汇总（asOf 与 compare 共用口径）。 */
    static Kpis kpis(List<WorkItem> items, Map<Long, String> statusAt) {
        int reqTotal = 0;
        int reqAccepted = 0;
        int defectsOpen = 0;
        int wip = 0;
        int risksOpen = 0;
        for (WorkItem w : items) {
            String status = statusAt.get(w.getId());
            if (status == null) {
                continue;
            }
            switch (w.getType()) {
                case "REQUIREMENT" -> {
                    reqTotal++;
                    if ("Accepted".equals(status)) {
                        reqAccepted++;
                    }
                }
                case "DEFECT" -> {
                    if (!"Closed".equals(status)) {
                        defectsOpen++;
                    }
                }
                case "RISK" -> {
                    if (!"Closed".equals(status) && !"Accepted".equals(status)) {
                        risksOpen++;
                    }
                }
                default -> { }
            }
            if (WIP_STATUSES.contains(status)) {
                wip++;
            }
        }
        return new Kpis(reqTotal, reqAccepted, defectsOpen, wip, risksOpen);
    }

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final WorkItemStatusLogMapper statusLogMapper;
    private final DecisionMapper decisionMapper;
    private final BaselineMapper baselineMapper;
    private final IterationMapper iterationMapper;
    private final EvidenceMapper evidenceMapper;

    public TimeMachineService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                              WorkItemStatusLogMapper statusLogMapper, DecisionMapper decisionMapper,
                              BaselineMapper baselineMapper, IterationMapper iterationMapper,
                              EvidenceMapper evidenceMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.decisionMapper = decisionMapper;
        this.baselineMapper = baselineMapper;
        this.iterationMapper = iterationMapper;
        this.evidenceMapper = evidenceMapper;
    }

    /** 事件轨道：项目全程范围 + 决策/基线/迭代起止标记（滑杆可点击跳转）。 */
    public Timeline timeline(Long projectId) {
        Project project = requireProject(projectId);
        List<EventPoint> events = new ArrayList<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).orderByAsc("id"))) {
            if (d.getDecidedAt() != null) {
                events.add(new EventPoint(d.getDecidedAt().toLocalDate(), "DECISION",
                        d.getCode() + " " + d.getConclusion(), d.getId()));
            }
        }
        for (Baseline b : baselineMapper.selectList(new QueryWrapper<Baseline>()
                .eq("project_id", projectId).orderByAsc("id"))) {
            events.add(new EventPoint(b.getCreatedAt().toLocalDate(), "BASELINE",
                    "基线 " + b.getName(), b.getId()));
        }
        for (Iteration it : iterationMapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", projectId))) {
            if (it.getStartDate() != null) {
                events.add(new EventPoint(it.getStartDate(), "ITER_START", it.getName() + " 开始", it.getId()));
            }
            if (it.getEndDate() != null) {
                events.add(new EventPoint(it.getEndDate(), "ITER_END", it.getName() + " 结束", it.getId()));
            }
        }
        LocalDate start = project.getCreatedAt() != null
                ? project.getCreatedAt().toLocalDate() : LocalDate.now();
        for (EventPoint e : events) {
            if (e.date().isBefore(start)) {
                start = e.date();
            }
        }
        events.sort((a, b) -> a.date().compareTo(b.date()));
        return new Timeline(start, LocalDate.now(), events);
    }

    /** 时点重建：回放状态日志得到 date 当天收盘时的项目状态。 */
    public AsOf asOf(Long projectId, LocalDate date) {
        requireProject(projectId);
        List<WorkItem> allItems = workItemMapper.selectAllIncludingDeleted(projectId);
        List<WorkItemStatusLog> logs = allItems.isEmpty() ? List.of()
                : statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                        .in("work_item_id", allItems.stream().map(WorkItem::getId).toList())
                        .orderByAsc("id"));
        Map<Long, String> statusAt = replay(allItems, logs, date);

        Map<String, Map<String, Integer>> byTypeStatus = new LinkedHashMap<>();
        List<AsOfItem> items = new ArrayList<>();
        int reqTotal = 0;
        int reqAccepted = 0;
        int defectsOpen = 0;
        int wip = 0;
        int risksOpen = 0;
        for (WorkItem w : allItems) {
            String status = statusAt.get(w.getId());
            if (status == null) {
                continue; // date 时点尚不存在
            }
            byTypeStatus.computeIfAbsent(w.getType(), k -> new LinkedHashMap<>())
                    .merge(status, 1, Integer::sum);
            items.add(new AsOfItem(w.getId(), w.getCode(), w.getType(), w.getTitle(), status,
                    w.getDeleted() != null && w.getDeleted() == 1));
            switch (w.getType()) {
                case "REQUIREMENT" -> {
                    reqTotal++;
                    if ("Accepted".equals(status)) {
                        reqAccepted++;
                    }
                }
                case "DEFECT" -> {
                    if (!"Closed".equals(status)) {
                        defectsOpen++;
                    }
                }
                case "RISK" -> {
                    if (!"Closed".equals(status) && !"Accepted".equals(status)) {
                        risksOpen++;
                    }
                }
                default -> { }
            }
            if (WIP_STATUSES.contains(status)) {
                wip++;
            }
        }
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        int decisionCount = Math.toIntExact(decisionMapper.selectCount(new QueryWrapper<Decision>()
                .eq("project_id", projectId).lt("decided_at", endOfDay)));
        int evidenceCount = Math.toIntExact(evidenceMapper.selectCount(new QueryWrapper<Evidence>()
                .eq("project_id", projectId).eq("category", "EVIDENCE").lt("created_at", endOfDay)));

        return new AsOf(date, reqTotal, reqAccepted, defectsOpen, wip, risksOpen,
                decisionCount, evidenceCount, byTypeStatus, items,
                dayEvents(projectId, date, allItems, logs));
    }

    /**
     * 回放纯函数：id → date 当天收盘状态；date 时点尚未创建的项不在结果中。
     * 状态 = 截至当天最后一条流转的 to_status；无流转则取状态机初始状态。
     */
    static Map<Long, String> replay(List<WorkItem> items, List<WorkItemStatusLog> logs, LocalDate date) {
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        Map<Long, String> statusAt = new HashMap<>();
        for (WorkItem w : items) {
            if (w.getCreatedAt() != null && !w.getCreatedAt().isBefore(endOfDay)) {
                continue;
            }
            WorkItemType type = WorkItemType.of(w.getType());
            statusAt.put(w.getId(), StateMachine.initialStatus(type));
        }
        for (WorkItemStatusLog log : logs) { // 已按 id 升序 = 时间序
            if (log.getAt() != null && log.getAt().isBefore(endOfDay)
                    && statusAt.containsKey(log.getWorkItemId())) {
                statusAt.put(log.getWorkItemId(), log.getToStatus());
            }
        }
        return statusAt;
    }

    /** 当日事件流：流转/决策/基线/证据。 */
    private List<DayEvent> dayEvents(Long projectId, LocalDate date,
                                     List<WorkItem> allItems, List<WorkItemStatusLog> logs) {
        Map<Long, WorkItem> byId = new HashMap<>();
        for (WorkItem w : allItems) {
            byId.put(w.getId(), w);
        }
        List<DayEvent> out = new ArrayList<>();
        for (WorkItem w : allItems) {
            if (w.getCreatedAt() != null && w.getCreatedAt().toLocalDate().equals(date)) {
                out.add(new DayEvent("CREATE", w.getCode() + " " + w.getTitle() + " 创建"));
            }
        }
        for (WorkItemStatusLog log : logs) {
            if (log.getAt() != null && log.getAt().toLocalDate().equals(date)) {
                WorkItem w = byId.get(log.getWorkItemId());
                if (w != null) {
                    out.add(new DayEvent("TRANSITION", w.getCode() + " " + w.getTitle()
                            + "：" + log.getFromStatus() + " → " + log.getToStatus()
                            + (log.getReason() != null && !log.getReason().isBlank()
                                    ? "（" + log.getReason() + "）" : "")));
                }
            }
        }
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId))) {
            if (d.getDecidedAt() != null && d.getDecidedAt().toLocalDate().equals(date)) {
                out.add(new DayEvent("DECISION", "决策 " + d.getCode() + " 结论 " + d.getConclusion()
                        + (d.getReason() != null ? "：" + d.getReason() : "")));
            }
        }
        for (Baseline b : baselineMapper.selectList(new QueryWrapper<Baseline>()
                .eq("project_id", projectId))) {
            if (b.getCreatedAt().toLocalDate().equals(date)) {
                out.add(new DayEvent("BASELINE", "建立基线 " + b.getName()
                        + "（" + b.getItemCount() + " 项）"));
            }
        }
        for (Evidence e : evidenceMapper.selectList(new QueryWrapper<Evidence>()
                .eq("project_id", projectId).eq("category", "EVIDENCE"))) {
            if (e.getCreatedAt() != null && e.getCreatedAt().toLocalDate().equals(date)) {
                out.add(new DayEvent("EVIDENCE", "上传证据 " + e.getCode() + " " + e.getFileName()));
            }
        }
        return out;
    }

    private Project requireProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        return p;
    }
}
