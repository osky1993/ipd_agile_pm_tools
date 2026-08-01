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

    public AuditService(AuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(Long projectId, String entityType, Long entityId, String action, String summary) {
        record(projectId, entityType, entityId, action, summary, null, null);
    }

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

    /** 查某实体的审计流水，供详情组件审计 Tab。 */
    public List<AuditEvent> listByEntity(String entityType, Long entityId) {
        return mapper.selectList(new QueryWrapper<AuditEvent>()
                .eq("entity_type", entityType)
                .eq("entity_id", entityId)
                .orderByDesc("at"));
    }

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
