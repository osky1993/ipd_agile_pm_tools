package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 追溯服务（T207 最小版）：建链/断链 + 给定对象的上下游查询，供统一详情组件关联 Tab。
 */
@Service
public class TraceLinkService {

    /** 允许的追溯关系（docs/01 §追溯关系枚举）。 */
    public static final Set<String> RELATIONS = Set.of(
            "contributes_to", "parent_of", "implements", "verifies", "blocks",
            "depends_on", "changes", "evidences", "affects", "released_in");

    private final TraceLinkMapper mapper;
    private final WorkItemMapper workItemMapper;
    private final AuditService audit;

    public TraceLinkService(TraceLinkMapper mapper, WorkItemMapper workItemMapper, AuditService audit) {
        this.mapper = mapper;
        this.workItemMapper = workItemMapper;
        this.audit = audit;
    }

    /** 关联视图：direction=OUT 表示 from 本对象指向 other；IN 表示 other 指向本对象。 */
    public record TraceView(Long linkId, String direction, String relation,
                            String otherType, Long otherId, String otherCode, String otherTitle) {
    }

    @Transactional
    public TraceLink create(TraceLink link) {
        if (!RELATIONS.contains(link.getRelation())) {
            throw new BusinessException("未知追溯关系: " + link.getRelation());
        }
        if (link.getSourceType().equals(link.getTargetType())
                && link.getSourceId().equals(link.getTargetId())) {
            throw new BusinessException("不能与自身建立追溯关系");
        }
        Long dup = mapper.selectCount(new QueryWrapper<TraceLink>()
                .eq("source_type", link.getSourceType()).eq("source_id", link.getSourceId())
                .eq("relation", link.getRelation())
                .eq("target_type", link.getTargetType()).eq("target_id", link.getTargetId()));
        if (dup != null && dup > 0) {
            throw new BusinessException("该追溯关系已存在");
        }
        link.setId(null);
        link.setCreatedBy(UserContext.currentUserId());
        link.setCreatedAt(LocalDateTime.now());
        mapper.insert(link);
        audit.record(link.getProjectId(), "TRACE_LINK", link.getId(), "CREATE",
                link.getSourceType() + "#" + link.getSourceId() + " -" + link.getRelation()
                        + "-> " + link.getTargetType() + "#" + link.getTargetId(), null, link);
        return link;
    }

    @Transactional
    public void delete(Long id) {
        TraceLink link = mapper.selectById(id);
        if (link == null) {
            return;
        }
        mapper.deleteById(id);
        audit.record(link.getProjectId(), "TRACE_LINK", id, "DELETE", "删除追溯关系", link, null);
    }

    /** 返回该对象的全部上下游关联（含正反向），并解析工作项标题。 */
    public List<TraceView> around(String objectType, Long objectId) {
        List<TraceView> views = new ArrayList<>();
        // 出边：本对象为 source
        for (TraceLink l : mapper.selectList(new QueryWrapper<TraceLink>()
                .eq("source_type", objectType).eq("source_id", objectId))) {
            views.add(toView(l.getId(), "OUT", l.getRelation(), l.getTargetType(), l.getTargetId()));
        }
        // 入边：本对象为 target
        for (TraceLink l : mapper.selectList(new QueryWrapper<TraceLink>()
                .eq("target_type", objectType).eq("target_id", objectId))) {
            views.add(toView(l.getId(), "IN", l.getRelation(), l.getSourceType(), l.getSourceId()));
        }
        return views;
    }

    private TraceView toView(Long linkId, String direction, String relation, String otherType, Long otherId) {
        String code = otherType + "#" + otherId;
        String title = "";
        if ("WORK_ITEM".equals(otherType)) {
            WorkItem wi = workItemMapper.selectById(otherId);
            if (wi != null) {
                code = wi.getCode();
                title = wi.getTitle();
            }
        }
        return new TraceView(linkId, direction, relation, otherType, otherId, code, title);
    }
}
