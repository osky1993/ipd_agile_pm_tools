package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Improvement;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.ImprovementMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程改进项闭环（指标驱动）：发现偏离 → 立改进项（记基线）→ 跟踪 → 验证实际效果。
 * 状态只前进：OPEN → DOING → DONE → VERIFIED；VERIFIED 必须回填实际值与基线对比。
 */
@Service
public class ImprovementService {

    private static final List<String> FLOW = List.of("OPEN", "DOING", "DONE", "VERIFIED");

    private final ImprovementMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final PerfService perfService;

    /**
     * 改进项服务依赖注入。
     * mapper 负责数据落库；projectMapper 提供项目信息与编码前缀；
     * codeGenerator 生成 IMP 编码；audit 记录全生命周期事件；perfService 用于指标改进基线对齐。
     */
    public ImprovementService(ImprovementMapper mapper, ProjectMapper projectMapper,
                              CodeGenerator codeGenerator, AuditService audit, PerfService perfService) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.perfService = perfService;
    }

    /**
     * 查询项目内改进项列表（只读）。
     * <p>可按状态筛选并按创建时间倒序返回；上层可据此构建分页和列表优先级。</p>
     *
     * @param projectId 项目 ID
     * @param status 状态过滤（OPEN/DOING/DONE/VERIFIED）
     */
    public List<Improvement> list(Long projectId, String status) {
        QueryWrapper<Improvement> qw = new QueryWrapper<Improvement>().eq("project_id", projectId);
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        return mapper.selectList(qw.orderByDesc("created_at").orderByDesc("id"));
    }

    /**
     * 创建一条改进项记录（PM-only，写链路）：
     * <ol>
     *   <li>校验项目存在与标题非空。</li>
     *   <li>若有 metricKey，先校验指标定义有效；未传基线时自动抓取当前指标值作为基线。</li>
     *   <li>补齐 code、默认状态、时间戳、操作者后入库。</li>
     *   <li>写入 CREATE 审计，形成生命周期起点。</li>
     * </ol>
     * <p>更新粒度：新增一条改进项主表记录。</p>
     * <p>失败策略：指标无效或数据库异常抛异常，事务回滚。</p>
     */
    @Transactional
    public Improvement create(Improvement in) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(in.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (in.getTitle() == null || in.getTitle().isBlank()) {
            throw new BusinessException("改进项标题不能为空");
        }
        if (in.getMetricKey() != null && !in.getMetricKey().isBlank()) {
            PerfService.def(in.getMetricKey())
                    .orElseThrow(() -> new BusinessException("未知指标: " + in.getMetricKey()));
            // 未显式给基线时，自动固化当前指标值为基线
            if (in.getBaselineValue() == null) {
                Double cur = perfService.currentValue(in.getProjectId(), in.getMetricKey());
                if (cur != null) {
                    in.setBaselineValue(BigDecimal.valueOf(cur));
                }
            }
        } else {
            in.setMetricKey(null);
        }
        Long uid = UserContext.currentUserId();
        in.setId(null);
        in.setCode(codeGenerator.next(project.getId(), project.getCode(), "IMP"));
        in.setStatus("OPEN");
        in.setResultValue(null);
        in.setConclusion(null);
        in.setCreatedBy(uid);
        in.setUpdatedBy(uid);
        in.setCreatedAt(LocalDateTime.now());
        in.setUpdatedAt(LocalDateTime.now());
        in.setDeleted(0);
        mapper.insert(in);
        audit.record(in.getProjectId(), "IMPROVEMENT", in.getId(), "CREATE",
                "发起改进 " + in.getCode() + " " + in.getTitle()
                        + (in.getMetricKey() != null ? "（指标 " + in.getMetricKey()
                        + " 基线 " + in.getBaselineValue() + "）" : ""), null, in);
        return in;
    }

    /**
     * 更新改进项（写链路）：
     * <ul>
     *   <li>仅允许 OPEN 或 DOING 状态编辑。</li>
     *   <li>仅处理非空 patch 字段，避免空值覆盖。</li>
     *   <li>更新后刷新更新时间和更新人，并记录 UPDATE 审计。</li>
     * </ul>
     * <p>更新粒度：只影响改进项元信息（标题、措施、目标值、截止日）。</p>
     */
    @Transactional
    public Improvement update(Long id, Improvement patch) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        if (!"OPEN".equals(old.getStatus()) && !"DOING".equals(old.getStatus())) {
            throw new BusinessException("仅 OPEN/DOING 状态可编辑");
        }
        if (patch.getTitle() != null) old.setTitle(patch.getTitle());
        if (patch.getMeasure() != null) old.setMeasure(patch.getMeasure());
        if (patch.getTargetValue() != null) old.setTargetValue(patch.getTargetValue());
        if (patch.getDueDate() != null) old.setDueDate(patch.getDueDate());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "UPDATE", "更新改进 " + old.getCode(), null, old);
        return old;
    }

    /**
     * 状态推进（写链路）：
     * <ol>
     *   <li>按 FLOW 强制顺序推进，不允许跳步或回退。</li>
     *   <li>进入 VERIFIED 需满足实际值；未传入则尝试按关联 metricKey 自动取当下值。</li>
     *   <li>写入结果值与结论（若传入），并刷新更新时间。</li>
     *   <li>记录 STATUS_CHANGE 审计，记录基线与实际值差分上下文。</li>
     * </ol>
     * <p>更新粒度：变更状态（及 VERIFIED 时的 resultValue/conclusion）。</p>
     */
    @Transactional
    public Improvement transition(Long id, String toStatus, BigDecimal resultValue, String conclusion) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        int from = FLOW.indexOf(old.getStatus());
        int to = FLOW.indexOf(toStatus);
        if (to < 0 || to != from + 1) {
            throw new BusinessException("非法流转: " + old.getStatus() + " → " + toStatus + "（只允许顺序前进）");
        }
        if ("VERIFIED".equals(toStatus)) {
            if (resultValue == null && old.getMetricKey() != null) {
                Double cur = perfService.currentValue(old.getProjectId(), old.getMetricKey());
                if (cur != null) {
                    resultValue = BigDecimal.valueOf(cur);
                }
            }
            if (resultValue == null) {
                throw new BusinessException("验证改进效果必须填写实际值");
            }
            old.setResultValue(resultValue);
            if (conclusion != null && !conclusion.isBlank()) {
                old.setConclusion(conclusion);
            }
        }
        old.setStatus(toStatus);
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "STATUS_CHANGE",
                "改进 " + old.getCode() + " → " + toStatus
                        + ("VERIFIED".equals(toStatus) ? "（基线 " + old.getBaselineValue()
                        + " → 实际 " + old.getResultValue() + "）" : ""), null, old);
        return old;
    }

    /**
     * 删除改进项（硬删，写链路）：
     * <ul>
     *   <li>先读取旧值，确保 DELETE 审计可回放。</li>
     *   <li>执行主表删除，不处理历史指标快照联动。</li>
     *   <li>PM-only 权限控制。</li>
     * </ul>
     */
    @Transactional
    public void delete(Long id) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        mapper.deleteById(id);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "DELETE", "删除改进 " + old.getCode(), null, null);
    }

    /**
     * 读取改进项详情（只读）：
     * <p>用于 update/transition/delete 前置校验；未命中抛 4040。</p>
     */
    private Improvement get(Long id) {
        Improvement imp = mapper.selectById(id);
        if (imp == null) {
            throw new BusinessException(4040, "改进项不存在");
        }
        return imp;
    }
}
