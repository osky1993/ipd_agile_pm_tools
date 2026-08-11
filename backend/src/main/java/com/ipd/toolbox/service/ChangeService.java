package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.*;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 变更服务（T401/T405）：影响分析（按 TraceLink 自动列出受影响的需求/测试/版本/DCP）+ 审批决策接入。
 * 影响分析完成置 ext_fields.impactAnalysed=true，守卫#5 方可放行进入 Impact Analysed。
 */
@Service
public class ChangeService {

    private final WorkItemMapper workItemMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final TestCaseMapper testCaseMapper;
    private final WorkItemService workItemService;
    private final DecisionService decisionService;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    /**
     * 变更服务依赖注入。
     * 变更影响分析需要 WorkItem/TraceLink/TestCase 的跨表查询能力，
     * workItemService/decisionService 负责状态流转与审批记录，audit 负责动作留痕。
     */
    public ChangeService(WorkItemMapper workItemMapper, TraceLinkMapper traceLinkMapper,
                         TestCaseMapper testCaseMapper, WorkItemService workItemService,
                         DecisionService decisionService, AuditService audit, ObjectMapper objectMapper) {
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.testCaseMapper = testCaseMapper;
        this.workItemService = workItemService;
        this.decisionService = decisionService;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public record ImpactItem(String category, String type, Long id, String code, String title, String via) {
    }

    public record ImpactResult(List<ImpactItem> items, int total) {
    }

    /**
     * 运行影响分析（写链路）：
     * <ol>
     *   <li>读取变更实体并校验类型（仅允许 CHANGE）。</li>
     *   <li>按 source→target 与受影响需求向外追踪 verifies/released_in 两类二跳关系。</li>
     *   <li>按 type/id 幂等去重并补齐展示字段。</li>
     *   <li>把 impactAnalysed/impactCount/impactAt 写回变更 ext_fields，标记分析完成态。</li>
     *   <li>记录 UPDATE 审计，便于后续审批链路校验。</li>
     * </ol>
     * <p>更新粒度：主更新为当前变更实体 ext_fields；其余对象仅读取生成分析快照。</p>
     * <p>失败策略：变更不存在或非变更类型抛出业务异常；数据库写失败整体抛异常。</p>
     */
    @Transactional
    public ImpactResult analyze(Long changeId) {
        WorkItem change = workItemMapper.selectById(changeId);
        if (change == null || !WorkItemType.CHANGE.name().equals(change.getType())) {
            throw new BusinessException("变更不存在");
        }
        Map<String, ImpactItem> collected = new LinkedHashMap<>();

        // Hop1：变更的出向关联对象
        List<TraceLink> out = traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("source_type", "WORK_ITEM").eq("source_id", changeId));
        for (TraceLink l : out) {
            addItem(collected, l.getTargetType(), l.getTargetId(), "变更 " + l.getRelation());
            // Hop2：受影响需求的测试与版本
            if ("WORK_ITEM".equals(l.getTargetType())) {
                Long reqId = l.getTargetId();
                for (TraceLink v : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                        .eq("target_type", "WORK_ITEM").eq("target_id", reqId).eq("relation", "verifies"))) {
                    addItem(collected, v.getSourceType(), v.getSourceId(), "验证受影响需求");
                }
                for (TraceLink r : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                        .eq("source_type", "WORK_ITEM").eq("source_id", reqId).eq("relation", "released_in"))) {
                    addItem(collected, r.getTargetType(), r.getTargetId(), "需求纳入版本");
                }
            }
        }
        List<ImpactItem> items = new ArrayList<>(collected.values());

        // 置 impactAnalysed=true + 摘要，供守卫#5 放行
        ObjectNode ext = readExt(change);
        ext.put("impactAnalysed", true);
        ext.put("impactCount", items.size());
        ext.put("impactAt", LocalDateTime.now().toString());
        WorkItem patch = new WorkItem();
        patch.setExtFields(ext.toString());
        workItemService.update(changeId, patch);

        audit.record(change.getProjectId(), "WORK_ITEM", changeId, "UPDATE",
                change.getCode() + " 完成影响分析，受影响对象 " + items.size() + " 项", null, null);
        return new ImpactResult(items, items.size());
    }

    /**
     * 审批变更（写链路）：
     * <ol>
     *   <li>校验 REVIEWER 角色与变更存在性。</li>
     *   <li>要求状态已进入 Impact Analysed，防止未分析就审批。</li>
     *   <li>记录 Decision，并同步触发变更状态流转到 Approved/Rejected。</li>
     * </ol>
     * <p>更新粒度：影响 Decision 新增 + WorkItem 状态同步（无直接修改关联关系）。</p>
     */
    @Transactional
    public Decision decide(Long changeId, boolean approve, String reason) {
        UserContext.requireRole("REVIEWER");
        WorkItem change = workItemMapper.selectById(changeId);
        if (change == null || !WorkItemType.CHANGE.name().equals(change.getType())) {
            throw new BusinessException("变更不存在");
        }
        if (!"Impact Analysed".equals(change.getStatus())) {
            throw new BusinessException("变更需先完成影响分析并进入 Impact Analysed 状态才能审批");
        }
        Decision d = new Decision();
        d.setProjectId(change.getProjectId());
        d.setDecisionType("CHANGE");
        d.setSubjectType("WORK_ITEM");
        d.setSubjectId(changeId);
        d.setConclusion(approve ? "APPROVED" : "REJECTED");
        d.setReason(reason);
        Decision saved = decisionService.record(d);

        // 变更状态流转（走状态机）
        workItemService.transition(changeId, approve ? "Approved" : "Rejected",
                "审批决策 " + saved.getCode());
        return saved;
    }

    /**
     * 将单条影响对象加入去重 map（纯计算）：
     * <ul>
     *   <li>以 <code>type#id</code> 去重，避免多条 traceLink 产生重复记录。</li>
     *   <li>补齐展示字段（code/title）供前端清单直接消费。</li>
     *   <li>不存在对象时仍记录“类型-其他”记录，保持列表完整性。</li>
     * </ul>
     *
     * @param map 去重目标容器
     * @param type 影响对象类型
     * @param id 影响对象 ID
     * @param via 影响发现路径
     */
    private void addItem(Map<String, ImpactItem> map, String type, Long id, String via) {
        String key = type + "#" + id;
        if (map.containsKey(key)) {
            return;
        }
        String category, code = type + "#" + id, title = "";
        switch (type) {
            case "WORK_ITEM" -> {
                WorkItem w = workItemMapper.selectById(id);
                category = w != null ? categoryOf(w.getType()) : "其他";
                if (w != null) { code = w.getCode(); title = w.getTitle(); }
            }
            case "TEST_CASE" -> {
                category = "测试";
                TestCase tc = testCaseMapper.selectById(id);
                if (tc != null) { code = tc.getCode(); title = tc.getTitle(); }
            }
            case "PRODUCT_VERSION" -> category = "版本";
            case "GATE_CRITERION" -> category = "DCP条件";
            default -> category = "其他";
        }
        map.put(key, new ImpactItem(category, type, id, code, title, via));
    }

    /**
     * 影响对象类型映射（只读）：
     * 统一 WorkItem 内码（DEFECT/RISK）到可读分类，其他类型默认落为需求。
     *
     * <p>仅影响展示口径，不参与数据库写入。</p>
     *
     * @param wtype 工作项类型编码
     * @return 中文展示分类
     */
    private String categoryOf(String wtype) {
        return switch (wtype == null ? "" : wtype) {
            case "DEFECT" -> "缺陷";
            case "RISK" -> "风险";
            default -> "需求";
        };
    }

    /**
     * 读取扩展字段为 JSON 对象（只读）：
     * <p>坏数据容忍策略：解析失败返回空对象，避免影响分析主流程可用性。
     * 同时不向上抛错，使治理任务可在后续补齐。</p>
     */
    private ObjectNode readExt(WorkItem w) {
        try {
            if (w.getExtFields() != null && !w.getExtFields().isBlank()) {
                return (ObjectNode) objectMapper.readTree(w.getExtFields());
            }
        } catch (Exception ignored) {
        }
        return objectMapper.createObjectNode();
    }
}
