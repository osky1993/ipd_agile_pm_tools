package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Evidence;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.EvidenceMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.mapper.WorkItemStatusLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报数据聚合：按时间窗 diff 出"这段时间发生了什么"——
 * 新增工作项、状态流转（含验收/关闭）、决策、证据。呈现层在前端报告页。
 */
@Service
public class WeeklyReportService {

    public record ActivityItem(Long id, String code, String type, String title,
                               String status, LocalDateTime createdAt) {
    }

    public record TransitionRow(Long workItemId, String code, String type, String title,
                                String fromStatus, String toStatus, String reason, LocalDateTime at) {
    }

    public record Summary(LocalDate since, LocalDate until,
                          List<ActivityItem> created, List<TransitionRow> transitions,
                          List<Decision> decisions, List<Evidence> evidences) {
    }

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final WorkItemStatusLogMapper statusLogMapper;
    private final DecisionMapper decisionMapper;
    private final EvidenceMapper evidenceMapper;

    public WeeklyReportService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                               WorkItemStatusLogMapper statusLogMapper,
                               DecisionMapper decisionMapper, EvidenceMapper evidenceMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.decisionMapper = decisionMapper;
        this.evidenceMapper = evidenceMapper;
    }

    public Summary summary(Long projectId, int days) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        int window = Math.min(Math.max(days, 1), 90);
        LocalDate until = LocalDate.now();
        LocalDate since = until.minusDays(window);
        LocalDateTime sinceTs = since.atStartOfDay();

        // 项目全部工作项索引（流转日志无 project 列，靠它归属与补充标题）
        Map<Long, WorkItem> itemById = new HashMap<>();
        for (WorkItem w : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId))) {
            itemById.put(w.getId(), w);
        }

        List<ActivityItem> created = new ArrayList<>();
        for (WorkItem w : itemById.values()) {
            if (w.getCreatedAt() != null && !w.getCreatedAt().isBefore(sinceTs)) {
                created.add(new ActivityItem(w.getId(), w.getCode(), w.getType(),
                        w.getTitle(), w.getStatus(), w.getCreatedAt()));
            }
        }
        created.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));

        List<TransitionRow> transitions = new ArrayList<>();
        if (!itemById.isEmpty()) {
            for (WorkItemStatusLog log : statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                    .ge("at", sinceTs).in("work_item_id", itemById.keySet()).orderByDesc("at"))) {
                WorkItem w = itemById.get(log.getWorkItemId());
                transitions.add(new TransitionRow(w.getId(), w.getCode(), w.getType(), w.getTitle(),
                        log.getFromStatus(), log.getToStatus(), log.getReason(), log.getAt()));
            }
        }

        List<Decision> decisions = decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).ge("decided_at", sinceTs).orderByDesc("id"));

        List<Evidence> evidences = evidenceMapper.selectList(new QueryWrapper<Evidence>()
                .eq("project_id", projectId).eq("category", "EVIDENCE")
                .ge("created_at", sinceTs).orderByDesc("id"));

        return new Summary(since, until, created, transitions, decisions, evidences);
    }
}
