package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 跨职能准备度（阶段4 · T601~T604）：五领域成熟度汇总 + 整机就绪判断。
 * 复用 GateCriterion（is_readiness=1）承载准备度检查项，领域为技术/质量/供应/制造/上市。
 */
@Service
public class ReadinessService {

    /** 五个准备度领域，固定顺序。 */
    public static final List<String> DOMAINS = List.of("技术", "质量", "供应", "制造", "上市");

    private final GateCriterionMapper criterionMapper;
    private final WorkItemMapper workItemMapper;

    public ReadinessService(GateCriterionMapper criterionMapper, WorkItemMapper workItemMapper) {
        this.criterionMapper = criterionMapper;
        this.workItemMapper = workItemMapper;
    }

    public record DomainReadiness(String domain, int total, int met, int partial, int notReady,
                                  int waived, List<String> redlineUnmet) {
        public int metPercent() {
            return total == 0 ? 0 : Math.round(met * 100f / total);
        }
    }

    /**
     * 整机就绪判断（T604）：识别"局部任务完成但整机/产品尚未准备好"。
     * ready = 无领域红线未满足 且 无未就绪检查项。
     */
    public record Overall(boolean ready, int reqTotal, int reqAccepted, List<String> reasons) {
    }

    public record Summary(List<DomainReadiness> domains, Overall overall) {
    }

    public List<GateCriterion> items(Long projectId, String domain) {
        QueryWrapper<GateCriterion> qw = new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_readiness", 1);
        if (domain != null && !domain.isBlank()) {
            qw.eq("domain", domain);
        }
        return criterionMapper.selectList(qw.orderByAsc("domain").orderByAsc("id"));
    }

    public Summary summary(Long projectId) {
        List<GateCriterion> all = criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_readiness", 1));

        Map<String, List<GateCriterion>> byDomain = new LinkedHashMap<>();
        for (String d : DOMAINS) {
            byDomain.put(d, new ArrayList<>());
        }
        for (GateCriterion c : all) {
            byDomain.computeIfAbsent(c.getDomain(), k -> new ArrayList<>()).add(c);
        }

        List<DomainReadiness> domains = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, List<GateCriterion>> e : byDomain.entrySet()) {
            int total = 0, met = 0, partial = 0, notReady = 0, waived = 0;
            List<String> redlineUnmet = new ArrayList<>();
            for (GateCriterion c : e.getValue()) {
                total++;
                switch (c.getStatus()) {
                    case "MET" -> met++;
                    case "PARTIAL" -> partial++;
                    case "WAIVED" -> waived++;
                    default -> notReady++;
                }
                boolean satisfied = "MET".equals(c.getStatus()) || "WAIVED".equals(c.getStatus());
                if (c.getIsRedline() != null && c.getIsRedline() == 1 && !satisfied) {
                    redlineUnmet.add(c.getCode());
                }
            }
            domains.add(new DomainReadiness(e.getKey(), total, met, partial, notReady, waived, redlineUnmet));
            if (!redlineUnmet.isEmpty()) {
                reasons.add(e.getKey() + "领域红线未满足：" + String.join("、", redlineUnmet));
            } else if (notReady > 0) {
                reasons.add(e.getKey() + "领域存在未就绪检查项 " + notReady + " 项");
            }
        }

        // 需求/能力完成度
        List<WorkItem> reqs = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId)
                .in("type", WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name()));
        int reqTotal = reqs.size();
        int reqAccepted = (int) reqs.stream().filter(w -> "Accepted".equals(w.getStatus())).count();

        boolean ready = reasons.isEmpty();
        return new Summary(domains, new Overall(ready, reqTotal, reqAccepted, reasons));
    }

    /** 项目级准备度红线未满足清单（供 DCP 判断纳入，规划§8.4）。 */
    public List<String> readinessRedlineUnmet(Long projectId) {
        List<String> codes = new ArrayList<>();
        for (GateCriterion c : criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_readiness", 1).eq("is_redline", 1))) {
            boolean satisfied = "MET".equals(c.getStatus()) || "WAIVED".equals(c.getStatus());
            if (!satisfied) {
                codes.add(c.getCode());
            }
        }
        return codes;
    }
}
