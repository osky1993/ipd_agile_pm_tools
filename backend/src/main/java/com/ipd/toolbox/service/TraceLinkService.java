package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
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

    /**
     * 追溯服务依赖的持久化与引用补齐组件都在这里注入。
     * - TraceLinkMapper：链路读写；
     * - WorkItemMapper：链路另一端为工作项时补齐 code/title；
     * - AuditService：所有新增/删除变更会记录可追溯审计。
     *
     * <p>更新口径说明：本服务不承担跨域写权限判断，由上层在业务语义层做角色与状态门禁。</p>
     */
    public TraceLinkService(TraceLinkMapper mapper, WorkItemMapper workItemMapper, AuditService audit) {
        this.mapper = mapper;
        this.workItemMapper = workItemMapper;
        this.audit = audit;
    }

    /**
     * 关联视图：direction=OUT 表示 from 本对象指向 other；IN 表示 other 指向本对象。
     *
     * <p>该视图用于前端图谱/详情页展示，不改变持久化模型，仅做一次对象补齐（当另一端为 WORK_ITEM 时补充 code/title）。</p>
     */
    public record TraceView(Long linkId, String direction, String relation,
                            String otherType, Long otherId, String otherCode, String otherTitle) {
    }

    /**
     * 创建追溯关系（含关系白名单校验、重复检测与审计）。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>关系白名单校验：不在 {@link #RELATIONS} 中直接拒绝。</li>
     *   <li>结构校验：禁止 source 与 target 完全同一对象形成自环。</li>
     *   <li>幂等策略：同源-同目标-同关系重复提交返回失败，而不是重复插入。</li>
     *   <li>持久化：统一注入创建人、创建时间后入库。</li>
     *   <li>副作用：写入 `TRACE_LINK CREATE` 审计，后续可按审计 ID 回放关系落库语义。</li>
     * </ul>
     * <p>失败策略：任一验证失败抛出业务异常并终止写入；重复关系直接拒绝，当前实现不做软重复合并。</p>
     *
     * @param link 待创建追溯关系实体（projectId/source/target/relation 均不能为空）
     * @return 入库后的追溯关系（含数据库生成 ID）
     */
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
                link.getSourceType() + "#" + link.getSourceId() + " 「" + Labels.relation(link.getRelation())
                        + "」 " + link.getTargetType() + "#" + link.getTargetId(), null, link);
        return link;
    }

    /**
     * 删除追溯关系。
     *
     * <p>边界：幂等删除。若 ID 不存在直接返回，不抛 NPE；若存在则先读出旧值再删除，
     * 之后记录 DELETE 审计并将旧镜像放入审计 before-image，便于事后追溯。</p>
     *
     * @param id 追溯关系 ID
     */
    @Transactional
    public void delete(Long id) {
        TraceLink link = mapper.selectById(id);
        if (link == null) {
            return;
        }
        mapper.deleteById(id);
        audit.record(link.getProjectId(), "TRACE_LINK", id, "DELETE", "删除追溯关系", link, null);
    }

    /**
     * 返回某对象的全部上下游关联（含正反向），并解析对端工作项标题。
     *
     * <p>查询维度：
     * <ul>
     *   <li>OUT：以传入对象为 source，表示该对象影响/指向他人。</li>
     *   <li>IN：以传入对象为 target，表示他人影响/指向该对象。</li>
     * </ul>
     * 不做去重：同向同关系的重复记录按数据库实际存在返回，需依赖上游治理。
     * <p>可用性边界：本方法执行两次 DB 扫描（IN/OUT），结果规模与对象复杂度线性相关；大规模图谱应结合分页或搜索端缓存。</p>
     *
     * @param objectType 对象类型
     * @param objectId 对象 ID
     */
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

    /**
     * 将追踪实体转为前端展示视图对象。
     *
     * <p>读模型转换规则：
     * <ul>
     *   <li>默认 `otherCode` 直接拼接为 `otherType#otherId`。</li>
     *   <li>当另一端是 WORK_ITEM 时按 ID 反查并补齐 code/title，用于 UI 友好展示。</li>
     *   <li>目标对象不存在时保持兜底空字符串，保持链路展示不因脏数据中断。</li>
     * </ul>
     * <p>副作用：无副作用，仅在 WorkItem 丢失时返回可读兜底值。</p>
     */
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
