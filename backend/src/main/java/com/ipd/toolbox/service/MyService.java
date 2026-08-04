package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.IterationMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「我的一天」跨项目工作台聚合（仿 ExecService 纯 Java for-loop 范式）。
 * 个人维度只在这里出现，AlertService 保持项目/流程级口径不动。
 */
@Service
public class MyService {

    static final int ITERATION_ENDING_DAYS = 7;
    private static final Set<String> DOING_STATUSES =
            Set.of("In Progress", "Analysing", "Fixing", "Mitigating");
    private static final Set<String> TERMINAL_STATUSES = Set.of("Accepted", "Closed");
    /** 进「我的一天」项目级预警的类型（个人视角最需要主动看到的少数几类） */
    private static final Set<String> ALERT_TYPES =
            Set.of("DCP_APPROACHING", "COMMITMENT_DUE", "WAIVER_DUE");

    public record MyItem(Long id, String code, String type, String title, String status,
                         String projectCode, String priority, LocalDate due) {
    }

    public record IterationEnding(Long id, String code, String name, String projectCode,
                                  LocalDate endDate, long daysLeft, long myOpenCount) {
    }

    public record Today(List<MyItem> inProgress, List<MyItem> overdue, List<MyItem> retest,
                        List<IterationEnding> endingSoon, List<AlertService.Alert> projectAlerts) {
    }

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final IterationMapper iterationMapper;
    private final AlertService alertService;
    private final PerfService perfService;

    public MyService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                     IterationMapper iterationMapper, AlertService alertService,
                     PerfService perfService) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.iterationMapper = iterationMapper;
        this.alertService = alertService;
        this.perfService = perfService;
    }

    public Today today(Long uid) {
        LocalDate today = LocalDate.now();
        List<Project> projects = projectMapper.selectList(new QueryWrapper<Project>()
                .ne("lifecycle_status", "CLOSED").orderByAsc("id"));
        Map<Long, String> codeByProject = new HashMap<>();
        for (Project p : projects) {
            codeByProject.put(p.getId(), p.getCode());
        }

        List<MyItem> inProgress = new ArrayList<>();
        List<MyItem> overdue = new ArrayList<>();
        List<MyItem> retest = new ArrayList<>();
        List<IterationEnding> endingSoon = new ArrayList<>();
        List<AlertService.Alert> projectAlerts = new ArrayList<>();

        for (Project p : projects) {
            collectMine(p, uid, today, codeByProject, inProgress, overdue, retest);
            collectIterations(p, uid, today, codeByProject, endingSoon);
            for (AlertService.Alert a : alertService.list(p.getId())) {
                if ("HIGH".equals(a.severity()) || ALERT_TYPES.contains(a.type())) {
                    projectAlerts.add(a);
                }
            }
        }
        overdue.sort((a, b) -> {
            LocalDate x = a.due() == null ? LocalDate.MAX : a.due();
            LocalDate y = b.due() == null ? LocalDate.MAX : b.due();
            return x.compareTo(y);
        });
        endingSoon.sort((a, b) -> a.endDate().compareTo(b.endDate()));
        return new Today(inProgress, overdue, retest, endingSoon, projectAlerts);
    }

    private void collectMine(Project p, Long uid, LocalDate today, Map<Long, String> codeByProject,
                             List<MyItem> inProgress, List<MyItem> overdue, List<MyItem> retest) {
        List<WorkItem> mine = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", p.getId()).eq("owner_id", uid)
                .notIn("status", TERMINAL_STATUSES));
        for (WorkItem w : mine) {
            String pc = codeByProject.get(w.getProjectId());
            if ("Retesting".equals(w.getStatus()) && "DEFECT".equals(w.getType())) {
                retest.add(toItem(w, pc, null));
            } else if (DOING_STATUSES.contains(w.getStatus())) {
                inProgress.add(toItem(w, pc, null));
            }
            // 超期识别：风险按 ext.dueDate，其余按 forecastDate
            LocalDate due = "RISK".equals(w.getType())
                    ? perfService.riskDueDate(w) : w.getForecastDate();
            if (due != null && due.isBefore(today)) {
                overdue.add(toItem(w, pc, due));
            }
        }
    }

    private void collectIterations(Project p, Long uid, LocalDate today,
                                   Map<Long, String> codeByProject, List<IterationEnding> out) {
        List<Iteration> active = iterationMapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", p.getId()).eq("status", "ACTIVE").isNotNull("end_date"));
        for (Iteration it : active) {
            if (it.getEndDate().isAfter(today.plusDays(ITERATION_ENDING_DAYS))) {
                continue;
            }
            Long myOpen = workItemMapper.selectCount(new QueryWrapper<WorkItem>()
                    .eq("iteration_id", it.getId()).eq("owner_id", uid)
                    .notIn("status", TERMINAL_STATUSES));
            out.add(new IterationEnding(it.getId(), it.getCode(), it.getName(),
                    codeByProject.get(it.getProjectId()), it.getEndDate(),
                    java.time.temporal.ChronoUnit.DAYS.between(today, it.getEndDate()), myOpen));
        }
    }

    private MyItem toItem(WorkItem w, String projectCode, LocalDate due) {
        return new MyItem(w.getId(), w.getCode(), w.getType(), w.getTitle(), w.getStatus(),
                projectCode, w.getPriority(), due);
    }
}
