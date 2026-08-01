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
     * 运行影响分析：从变更出发，收集其 changes/affects 指向的对象，
     * 再对受影响需求追溯 verifies 的测试用例与 released_in 的版本，分类返回。
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

    /** 审批决策（T405）：授权角色作出，生成 Decision，并将变更流转到 Approved/Rejected。 */
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

    private String categoryOf(String wtype) {
        return switch (wtype == null ? "" : wtype) {
            case "DEFECT" -> "缺陷";
            case "RISK" -> "风险";
            default -> "需求";
        };
    }

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
