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
 * 风险深化：概率×影响定性评估（ext_fields 键 probability/impact/strategy）与风险任务化。
 * 敞口 exposure = p×i 读时计算不落库（避免双写不一致）；等级 高≥15 / 中≥8 / 低。
 * 任务化建链用 TASK -affects→ RISK：不扩 mitigates 关系——关系枚举散布在 V1 注释/docs/
 * MCP 描述/labels 四处，而 (TASK, affects, RISK) 三元组全站唯一，可精确识别应对任务。
 */
@Service
public class RiskService {

    public record RiskExt(String mitigation, LocalDate dueDate,
                          Integer probability, Integer impact, String strategy) {
    }

    /** 静态便捷解析用（只读原始类型，无需注入配置过的 ObjectMapper） */
    private static final ObjectMapper PLAIN_OM = new ObjectMapper();

    static RiskExt parseExt(String extJson) {
        return parseExt(extJson, PLAIN_OM);
    }

    private final WorkItemService workItemService;
    private final TraceLinkService traceLinkService;
    private final com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public RiskService(WorkItemService workItemService, TraceLinkService traceLinkService,
                       com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper,
                       AuditService audit, ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.traceLinkService = traceLinkService;
        this.traceLinkMapper = traceLinkMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** 容错读 ext（坏 JSON/缺键/字符串数字均不抛错）。 */
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

    static Integer exposure(RiskExt e) {
        if (e.probability() == null || e.impact() == null) {
            return null;
        }
        return e.probability() * e.impact();
    }

    /** HIGH ≥15 / MED ≥8 / LOW；未评估返回 null。 */
    static String exposureLevel(Integer exposure) {
        if (exposure == null) {
            return null;
        }
        return exposure >= 15 ? "HIGH" : exposure >= 8 ? "MED" : "LOW";
    }

    /** 风险任务化：按处置措施生成应对 TASK 并建 TASK -affects→ RISK 追溯链（防重）。 */
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

    private static String textOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

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
