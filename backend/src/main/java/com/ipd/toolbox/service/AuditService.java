package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.domain.entity.AuditEvent;
import com.ipd.toolbox.mapper.AuditEventMapper;
import com.ipd.toolbox.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计服务（T106）。所有状态变化、责任人变化、决策提交都应经此落审计。
 * 业务代码调用 record 系列方法，who 由 UserContext 自动补齐。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 审计服务依赖注入。
     * mapper 落库审计事件，objectMapper 负责 before/after 快照 JSON 化失败时降级为字符串。
     * 审计服务自身不决定鉴权边界，调用方应已完成权限校验。
     */
    public AuditService(AuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录审计事件（不带变更前/后快照）。
     *
     * <p>业务事件由调用方提供 summary 与实体信息，审计人由 UserContext 自动注入。
     * 常用于轻量记录如查询、状态查询行为。</p>
     *
     * @param projectId 关联项目
     * @param entityType 实体类型
     * @param entityId 实体主键
     * @param action 动作类型
     * @param summary 中文摘要
     */
    public void record(Long projectId, String entityType, Long entityId, String action, String summary) {
        record(projectId, entityType, entityId, action, summary, null, null);
    }

    /**
     * 记录审计事件（带变更前后快照）。
     *
     * <p>before/after 对象会做 JSON 兜底序列化，仅失败不阻断主业务。
     * 审计日志默认用于追责和回放，不作为主数据真值来源。</p>
     *
     * @param projectId 关联项目
     * @param entityType 实体类型
     * @param entityId 实体主键
     * @param action 动作类型
     * @param summary 中文摘要
     * @param before 变更前快照（可为 null）
     * @param after 变更后快照（可为 null）
     */
    public void record(Long projectId, String entityType, Long entityId, String action,
                        String summary, Object before, Object after) {
        AuditEvent e = new AuditEvent();
        e.setProjectId(projectId);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setAction(action);
        e.setActorId(UserContext.currentUserId());
        e.setSummary(summary);
        e.setBeforeJson(toJson(before));
        e.setAfterJson(toJson(after));
        e.setAt(LocalDateTime.now());
        mapper.insert(e);
    }

    /**
     * 查询某实体的审计流水（倒序）。
     *
     * <p>用于详情页审计 Tab 与变更追溯，不做分页；
     * 调用侧可在上层增加时间窗/分页能力。</p>
     *
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @return 审计事件列表
     */
    public List<AuditEvent> listByEntity(String entityType, Long entityId) {
        return mapper.selectList(new QueryWrapper<AuditEvent>()
                .eq("entity_type", entityType)
                .eq("entity_id", entityId)
                .orderByDesc("at"));
    }

    /**
     * 将对象序列化为审计 JSON 快照。
     * 空对象返回 null；序列化失败仅记录 warn 并退化为 toString，避免主流程阻塞。
     */
    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception ex) {
            log.warn("审计序列化失败: {}", ex.getMessage());
            return String.valueOf(o);
        }
    }
}
