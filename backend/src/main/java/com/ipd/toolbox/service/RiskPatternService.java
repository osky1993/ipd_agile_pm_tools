package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.mapper.WorkItemStatusLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨项目风险模式库（只读聚合）：按风险口径输出状态分布、等级分布与文本关键词。
 * 数据源覆盖历史 CLOSED 项目，便于趋势与模式对齐。
 */
@Service
public class RiskPatternService {

    public record RiskRow(Long id, String code, String projectCode, String title, String status,
                          Integer exposure, String exposureLevel, String strategy,
                          Long resolveDays) {
    }

    public record WordFreq(String word, int count) {
    }

    public record Patterns(int total, int closed, int accepted, int open,
                           Double avgResolveDays,
                           Map<String, Integer> byLevel, Map<String, Integer> byStrategy,
                           List<WordFreq> topWords, List<RiskRow> rows) {
    }

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final WorkItemStatusLogMapper statusLogMapper;

    /**
     * 风险模式服务依赖注入。
     * 需要 project/workItem/statusLog 三类数据完成全量风险抽样、状态流转闭环时长统计与项目码映射。
     */
    public RiskPatternService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                              WorkItemStatusLogMapper statusLogMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.statusLogMapper = statusLogMapper;
    }

    /**
     * 汇总风险模式数据（读模型）：
     * <ol>
     *   <li>聚合全量风险及项目映射表用于展示。</li>
     *   <li>批量读取状态日志，按首流转到闭环状态计算处置时长。</li>
     *   <li>交给 aggregate() 做统一过滤与统计。</li>
     *   <li>若 keyword 非空，执行标题包含过滤。</li>
     * </ol>
     * <p>该方法不落库，仅供分析面板消费。</p>
     */
    public Patterns patterns(String keyword) {
        Map<Long, String> codeByProject = new HashMap<>();
        for (Project p : projectMapper.selectList(null)) {
            codeByProject.put(p.getId(), p.getCode());
        }
        List<WorkItem> risks = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("type", "RISK").orderByDesc("id"));
        // 处置时长：首条流转 → 最后进入终态（批量查状态日志，避免 N+1）
        Map<Long, Long> resolveDays = new HashMap<>();
        if (!risks.isEmpty()) {
            Map<Long, LocalDateTime> firstLog = new HashMap<>();
            Map<Long, LocalDateTime> doneLog = new HashMap<>();
            for (WorkItemStatusLog log : statusLogMapper.selectList(new QueryWrapper<WorkItemStatusLog>()
                    .in("work_item_id", risks.stream().map(WorkItem::getId).toList()).orderByAsc("id"))) {
                firstLog.putIfAbsent(log.getWorkItemId(), log.getAt());
                if ("Closed".equals(log.getToStatus()) || "Accepted".equals(log.getToStatus())) {
                    doneLog.put(log.getWorkItemId(), log.getAt());
                }
            }
            for (WorkItem r : risks) {
                LocalDateTime start = r.getCreatedAt() != null ? r.getCreatedAt() : firstLog.get(r.getId());
                LocalDateTime done = doneLog.get(r.getId());
                if (start != null && done != null && !done.isBefore(start)) {
                    resolveDays.put(r.getId(), ChronoUnit.DAYS.between(start, done));
                }
            }
        }
        return aggregate(risks, codeByProject, resolveDays, keyword);
    }

    /**
     * 聚合纯函数（静态、可复用）：
     * <ul>
     *   <li>按 keyword 过滤风险标题。</li>
     *   <li>累计状态、等级、策略、处置天数与 bigram 词频。</li>
     *   <li>输出 rows、closed/accepted/open 与平均处置时长。</li>
     * </ul>
     * <p>纯计算，不含数据库副作用。</p>
     */
    static Patterns aggregate(List<WorkItem> risks, Map<Long, String> codeByProject,
                              Map<Long, Long> resolveDays, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<RiskRow> rows = new ArrayList<>();
        int closed = 0;
        int accepted = 0;
        long resolveSum = 0;
        int resolveCount = 0;
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byStrategy = new LinkedHashMap<>();
        Map<String, Integer> freq = new HashMap<>();

        for (WorkItem r : risks) {
            if (!kw.isEmpty() && (r.getTitle() == null || !r.getTitle().contains(kw))) {
                continue;
            }
            RiskService.RiskExt ext = RiskService.parseExt(r.getExtFields());
            Integer exposure = RiskService.exposure(ext);
            String level = RiskService.exposureLevel(exposure);
            Long days = resolveDays.get(r.getId());
            rows.add(new RiskRow(r.getId(), r.getCode(), codeByProject.get(r.getProjectId()),
                    r.getTitle(), r.getStatus(), exposure, level, ext.strategy(), days));
            if ("Closed".equals(r.getStatus())) {
                closed++;
            } else if ("Accepted".equals(r.getStatus())) {
                accepted++;
            }
            if (days != null) {
                resolveSum += days;
                resolveCount++;
            }
            if (level != null) {
                byLevel.merge(level, 1, Integer::sum);
            }
            if (ext.strategy() != null) {
                byStrategy.merge(ext.strategy(), 1, Integer::sum);
            }
            countBigrams(r.getTitle(), freq);
        }
        List<WordFreq> topWords = freq.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20)
                .map(e -> new WordFreq(e.getKey(), e.getValue()))
                .toList();
        Double avg = resolveCount == 0 ? null
                : Math.round(resolveSum * 10.0 / resolveCount) / 10.0;
        return new Patterns(rows.size(), closed, accepted, rows.size() - closed - accepted,
                avg, byLevel, byStrategy, topWords, rows);
    }

    /**
     * 中文 bigram 词频提取（只读）：
     * <ul>
     *   <li>仅保留 CJK 片段，按相邻两个字符计数。</li>
     *   <li>只保留频次≥2 的词条作为 topWords 候选。</li>
     * </ul>
     * <p>该策略避免外部分词依赖，聚焦内部一致性和部署轻量。</p>
     */
    static void countBigrams(String title, Map<String, Integer> freq) {
        if (title == null) {
            return;
        }
        StringBuilder cjk = new StringBuilder();
        for (char c : title.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk.append(c);
            } else {
                cjk.append(' ');
            }
        }
        for (String seg : cjk.toString().split("\\s+")) {
            for (int i = 0; i + 1 < seg.length(); i++) {
                freq.merge(seg.substring(i, i + 2), 1, Integer::sum);
            }
        }
    }
}
