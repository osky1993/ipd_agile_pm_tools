package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 决策服务（T404）。决策只增不改：修订必须通过新记录并 link prevDecisionId，不无痕覆盖历史（规划§7.3）。
 * 正式决策由授权角色作出（REVIEWER/ADMIN）——决策权不可被系统评分替代（规划§2.4）。
 */
@Service
public class DecisionService {

    private final DecisionMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    /**
     * 决策服务依赖主表与项目/代码生成/审计组件注入，
     * 其中项目用于决策号命名，审计用于写入可追溯记录。
     */
    public DecisionService(DecisionMapper mapper, ProjectMapper projectMapper,
                           CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    /**
     * 按项目读取历史决策流水（最新在前），用于决策列表页与变更追溯。
     *
     * <p>范围：仅按 `project_id` 过滤，按 `decided_at` 降序返回，默认展示完整决策链。</p>
     * <p>纯读方法，不会重建修订关系，不产生审计副作用。</p>
     */
    public List<Decision> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).orderByDesc("decided_at"));
    }

    /**
     * 获取某个 subject 的最新决策。
     *
     * <p>用途：作为 `record` 的 prevDecisionId 自动补齐来源，形成修订链。</p>
     * <p>边界：subjectType/subjectId 为空直接返回 null，避免调用方传入不完整参数时抛异常。</p>
     * <p>无副作用：仅执行单次 selectOne 查询，找不到返回 null。</p>
     *
     * @param subjectType 主题类型（例如 STAGE_GATE/WORK_ITEM）
     * @param subjectId 主题 ID
     * @return 指定 subject 最近决策；不存在返回 null
     */
    public Decision latestFor(String subjectType, Long subjectId) {
        if (subjectType == null || subjectId == null) {
            return null;
        }
        return mapper.selectOne(new QueryWrapper<Decision>()
                .eq("subject_type", subjectType).eq("subject_id", subjectId)
                .orderByDesc("id").last("LIMIT 1"));
    }

    /**
     * 记录一条正式决策（只增），并建立修订链。
     *
     * <p>更新粒度（先决条件 + 更新动作 + 约束）：</p>
     * <ul>
     *   <li>先决条件：`REVIEWER` 角色，项目存在，结论非空。</li>
     *   <li>动作：
     *     <ol>
     *       <li>清空主键，确保插入新行而非更新。</li>
     *       <li>当 `prevDecisionId` 为空时按 subjectType/subjectId 自动补齐最新决策，确保修订链可追溯。</li>
     *       <li>生成 `DEC` 编码，填充决策人和决策时间后插入。</li>
     *       <li>写 `DECISION` 审计。</li>
     *     </ol>
     *   </li>
     * </ul>
     * <p>幂等边界：本方法天然不幂等；重复提交会创建更多历史记录。若调用方需要幂等，请在上游按 subject + 业务标识去重。</p>
     * <p>失败策略：事务内失败全部回滚，`prevDecisionId` 自动补齐与代码生成与插入失败均不会部分落库。</p>
     *
     * @param d 决策对象（结论与主题必填）
     * @return 持久化后的决策
     */
    @Transactional
    public Decision record(Decision d) {
        UserContext.requireRole("REVIEWER");
        Project project = projectMapper.selectById(d.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (d.getConclusion() == null || d.getConclusion().isBlank()) {
            throw new BusinessException("决策结论不能为空");
        }
        d.setId(null);
        // 修订链：同 subject 已有决策则自动串到最新一条（调用方显式给 prev 时尊重之）
        if (d.getPrevDecisionId() == null) {
            Decision latest = latestFor(d.getSubjectType(), d.getSubjectId());
            if (latest != null) {
                d.setPrevDecisionId(latest.getId());
            }
        }
        d.setCode(codeGenerator.next(project.getId(), project.getCode(), "DEC"));
        d.setDecidedBy(UserContext.currentUserId());
        d.setDecidedAt(LocalDateTime.now());
        mapper.insert(d);
        audit.record(d.getProjectId(), "DECISION", d.getId(), "DECISION",
                "决策 " + d.getCode() + " [" + d.getDecisionType() + "] 结论=" + d.getConclusion()
                        + (d.getPrevDecisionId() != null ? "（修订自 #" + d.getPrevDecisionId() + "）" : ""),
                null, d);
        return d;
    }
}
