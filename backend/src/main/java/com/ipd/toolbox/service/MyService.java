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
    /** 进行中状态集合：用于“我的一天”中的“进行中”列表构建。 */
    private static final Set<String> DOING_STATUSES =
            Set.of("In Progress", "Analysing", "Fixing", "Mitigating");
    /** 终态集合：用于超期统计时排除已结束项。 */
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

    /**
     * 我的工作台服务依赖注入。
     * 汇总层跨 project、work item、iteration，最终输出前端“我的一天”所需条目；
     * alert/perf 分别提供项目级告警与风险 dueDate 口径。
     */
    public MyService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                     IterationMapper iterationMapper, AlertService alertService,
                     PerfService perfService) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.iterationMapper = iterationMapper;
        this.alertService = alertService;
        this.perfService = perfService;
    }

    /**
     * 汇总“我的一天”视图：
     * - inProgress：本人当前进行中项
     * - overdue：本人超期项（按截止日升序）
     * - retest：本人待复测缺陷
     * - endingSoon：本人参与的近 7 天迭代
     * - projectAlerts：项目级高优先预警（HIGH + 指定类型）
     *
     * <p>更新粒度与边界：
     * <ul>
     *   <li>仅聚合非关闭项目；个人视图跨项目汇总。</li>
     *   <li>inProgress 依据 `DOING_STATUSES` 判定；retest 仅 `DEFECT + Retesting`。</li>
     *   <li>overdue 使用风险 dueDate（Risk）与 forecastDate（其他类型）统一为 `due`；过期则入列表。</li>
     *   <li>endingSoon 从参与者的 ACTIVE 迭代中过滤，按结束日期升序返回。</li>
     *   <li>projectAlerts 取项目告警中 HIGH + 白名单类型（DCP/承诺/豁免）。</li>
     * </ul>
     * <p>无持久化副作用。</p>
     */
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
        // 注意：该查询只排除终态，状态枚举新增时需要同步 `TERMINAL_STATUSES` 与 DOING_STATUSES。
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

    /**
     * 收集本人相关且即将截止的迭代。  
     * 返回结果用于首页“迭代冲刺预警”区块。
     *
     * <p>更新边界：只扫该项目 ACTIVE 并有 end_date 的迭代；提前窗口为 7 天（`ITERATION_ENDING_DAYS`）。</p>
     * <p>返回值按结束日期升序排序，以便前端卡片直接按时间展示。</p>
     */
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

    /**
     * 将 WorkItem 映射为页面展示记录，避免服务层直接泄漏持久化实体差异。
     *
     * <p>映射策略：仅复制 UI 需要字段，不注入任何派生字段（e.g. riskLevel）。</p>
     */
    private MyItem toItem(WorkItem w, String projectCode, LocalDate due) {
        return new MyItem(w.getId(), w.getCode(), w.getType(), w.getTitle(), w.getStatus(),
                projectCode, w.getPriority(), due);
    }
}
