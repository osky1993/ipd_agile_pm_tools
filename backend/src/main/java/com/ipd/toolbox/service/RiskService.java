package com.ipd.toolbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 风险深化服务：风险敞口计算、等级映射、风险任务化。
 * <p>约束：exposure 在读取口径计算，不落库；坏数据解析优先降级为空值以保证流程连续性。</p>
 */
@Service
public class RiskService {

    public record RiskExt(String mitigation, LocalDate dueDate,
                          Integer probability, Integer impact, String strategy) {
    }

    /** 静态便捷解析用（只读原始类型，无需注入配置过的 ObjectMapper）。 */
    private static final ObjectMapper PLAIN_OM = new ObjectMapper();

    static RiskExt parseExt(String extJson) {
        return parseExt(extJson, PLAIN_OM);
    }

    private final WorkItemService workItemService;
    private final TraceLinkService traceLinkService;
    private final com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    /**
     * 风险服务依赖注入。
     * WorkItemService 负责风险对象的 CRUD 与状态校验，
     * TraceLinkService 负责链路创建，
     * traceLinkMapper + audit 提供持久化存在性与审计留痕。
     */
    public RiskService(WorkItemService workItemService, TraceLinkService traceLinkService,
                       com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper,
                       AuditService audit, ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.traceLinkService = traceLinkService;
        this.traceLinkMapper = traceLinkMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取扩展字段并将 probability/impact/mitigation 转为领域对象（只读）：
     * <ul>
     *   <li>缺失或空 JSON 返回默认空对象，避免主流程异常中断。</li>
     *   <li>解析失败降级为空对象，保留容错边界。</li>
     * </ul>
     */
    static RiskExt parseExt(String extJson, ObjectMapper om) {
        if (extJson == null || extJson.isBlank()) {
            return new RiskExt(null, null, null, null, null);
        }
        try {
            JsonNode n = om.readTree(extJson);
            return new RiskExt(
                    textOrNull(n, "mitigation"),
                    dateOrNull(n, "dueDate"),
                    intOrNull(n, "probability"),
                    intOrNull(n, "impact"),
                    textOrNull(n, "strategy"));
        } catch (Exception e) {
            return new RiskExt(null, null, null, null, null);
        }
    }

    /**
     * 计算敞口（只读）：probability × impact。
     * 任一输入缺失返回 null；该值不落库，按读取口径派生，避免主数据冗余。
     */
    static Integer exposure(RiskExt e) {
        if (e.probability() == null || e.impact() == null) {
            return null;
        }
        return e.probability() * e.impact();
    }

    /**
     * 按敞口阈值映射等级（只读）：
     * <ul>
     * <li>HIGH: exposure ≥ 15</li>
     * <li>MED: exposure ≥ 8</li>
     * <li>LOW: 其他已评估场景</li>
     * <li>null：缺少评估输入</li>
     * </ul>
     */
    static String exposureLevel(Integer exposure) {
        if (exposure == null) {
            return null;
        }
        return exposure >= 15 ? "HIGH" : exposure >= 8 ? "MED" : "LOW";
    }

    /**
     * 从风险生成应对任务（写链路）：
     * <ol>
     *   <li>校验目标是 RISK 类型。</li>
     *   <li>解析 ext_fields 确认 mitigation 已填写，未填写直接失败。</li>
     *   <li>防重：已存在 TARGET=RISK 的 affects 关系时拒绝重复创建。</li>
     *   <li>创建 TASK 并建立 TASK -affects→ RISK 关系。</li>
     *   <li>写入风险 UPDATE 审计。</li>
     * </ol>
     * <p>更新粒度：新增任务与单条追溯关系，不会修改原风险主记录内容。</p>
     * <p>失败策略：任何异常会阻断任务化，避免生成孤儿任务。</p>
     *
     * @param riskId 风险工作项 ID
     * @return 新建的 TASK 实体
     */
    @Transactional
    public WorkItem createMitigationTask(Long riskId) {
        WorkItem risk = workItemService.get(riskId);
        if (!"RISK".equals(risk.getType())) {
            throw new BusinessException("仅风险可生成应对任务");
        }
        RiskExt ext = parseExt(risk.getExtFields(), objectMapper);
        if (ext.mitigation() == null || ext.mitigation().isBlank()) {
            throw new BusinessException("请先填写处置措施（mitigation），应对任务以它为内容");
        }
        // 防重：已存在 TASK -affects→ 本风险 的链则拒绝重复生成
        Long existing = traceLinkMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TraceLink>()
                        .eq("target_type", "WORK_ITEM").eq("target_id", riskId)
                        .eq("relation", "affects").eq("source_type", "WORK_ITEM"));
        if (existing != null && existing > 0) {
            throw new BusinessException("该风险已有应对任务（affects 链已存在），请在依赖网络或详情关联中查看");
        }
        WorkItem task = new WorkItem();
        task.setProjectId(risk.getProjectId());
        task.setType("TASK");
        task.setTitle("应对：" + risk.getTitle());
        task.setDescription(ext.mitigation());
        task.setOwnerId(risk.getOwnerId());
        task.setPriority(risk.getPriority());
        task.setForecastDate(ext.dueDate());
        WorkItem created = workItemService.create(task, null);

        TraceLink link = new TraceLink();
        link.setProjectId(risk.getProjectId());
        link.setSourceType("WORK_ITEM");
        link.setSourceId(created.getId());
        link.setTargetType("WORK_ITEM");
        link.setTargetId(riskId);
        link.setRelation("affects");
        traceLinkService.create(link);

        audit.record(risk.getProjectId(), "WORK_ITEM", riskId, "UPDATE",
                "风险任务化：生成应对任务 " + created.getCode() + "（affects 链）", null, null);
        return created;
    }

    /**
     * 读取 JSON 文本值（只读）：空字符串返回 null，避免空值污染文本语义。
     */
    private static String textOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    /**
     * 解析 int（只读）：允许 int/数字字符串，非法格式返回 null。
     */
    private static Integer intOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isInt()) {
            return v.asInt();
        }
        if (v.isTextual()) {
            try {
                return Integer.parseInt(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析 ISO 日期（只读）：非法格式返回 null。
     */
    private static LocalDate dateOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (!v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
