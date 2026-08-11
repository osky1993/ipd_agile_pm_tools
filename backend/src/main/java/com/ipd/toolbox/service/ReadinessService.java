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

    /**
     * 构造注入：就绪域评估同时依赖检查项元数据与需求能力状态。
     * 通过这两个 mapper 将“门槛完成率”和“需求落地度”在同一份摘要里返回。
     */
    public ReadinessService(GateCriterionMapper criterionMapper, WorkItemMapper workItemMapper) {
        this.criterionMapper = criterionMapper;
        this.workItemMapper = workItemMapper;
    }

    public record DomainReadiness(String domain, int total, int met, int partial, int notReady,
                                  int waived, List<String> redlineUnmet) {
        /**
         * 按当前域内完成项比例返回 MET 口径完成率（百分比）。
         */
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

    /**
     * 读取准备度检查项。
     *
     * @param projectId 项目 ID
     * @param domain 可选域过滤；空则返回全部域
     * @return 域内所有就绪项，按 domain 和 id 排序
     *
     * <p>仅读 is_readiness=1 的检查项。domain 为空时不做过滤并返回全部域，便于 readiness 汇总复用。</p>
     */
    public List<GateCriterion> items(Long projectId, String domain) {
        QueryWrapper<GateCriterion> qw = new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_readiness", 1);
        if (domain != null && !domain.isBlank()) {
            qw.eq("domain", domain);
        }
        return criterionMapper.selectList(qw.orderByAsc("domain").orderByAsc("id"));
    }

    /**
     * 计算项目就绪综合视图。
     *
     * <p>域聚合统计每域 MET/PARTIAL/WAIVED/未就绪数，并汇总红线未满足项；
     * 同时计算需求/能力 Accepted 进度用于整体是否可投放的辅助判定。</p>
     *
     * <p>更新粒度说明：
     * 本方法无数据库写入；当任一域红线未满足时 reasons 非空；全部通过时 reasons 空且 ready=true。
     * 未命中项（空列表）会被计为 total=0、met/partial/notReady/waived 全 0。</p>
     *
     * @param projectId 项目 ID
     * @return 域级指标 + 整机就绪结论（ready=true 时 reasons 为空）
     */
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

    /**
     * 读取项目级红线未满足清单。
     *
     * <p>只返回 is_redline=1 且状态非 MET/WAIVED 的检查项编码，用于决策面板和 DCP 提前提醒；
     * 结果不排序，按数据库返回顺序，强调“配置变更后的自然顺序”。</p>
     *
     * @param projectId 项目 ID
     * @return 未满足红线代码列表
     */
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
