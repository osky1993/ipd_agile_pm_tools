package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.StageGateMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
/**
 * 阶段门（Stage/Gate）服务。
 * <p>
 * 管理项目内阶段门的创建与更新，承接排期与流程控制场景。
 * 写操作均要求 PM 权限，并记录审计事件，用于变更可追溯。
 */
public class StageGateService {

    private final StageGateMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    /**
     * 阶段门服务依赖注入。
     * mapper 持久化阶段门，projectMapper 做项目校验，
     * codeGenerator 生成 DCP 编码，audit 负责创建与更新留痕。
     */
    public StageGateService(StageGateMapper mapper, ProjectMapper projectMapper,
                            CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    /**
     * 查询指定项目的阶段门列表（只读）。
     * <p>结果按 seq/id 递增返回，保持 DCP 流程顺序稳定；上层可直接用于时间线渲染。</p>
     *
     * @param projectId 项目 ID
     * @return 阶段门列表
     */
    public List<StageGate> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<StageGate>()
                .eq("project_id", projectId).orderByAsc("seq").orderByAsc("id"));
    }

    /**
     * 创建阶段门（写链路）：
     * <ol>
     *   <li>校验 PM 权限与项目存在性。</li>
     *   <li>生成主键/编码/序号等默认值并补齐审计元数据。</li>
     *   <li>入库后写 CREATE 审计。</li>
     * </ol>
     * <p>更新粒度：新增一条 STAGE_GATE 记录；无额外联表副作用。</p>
     * <p>失败策略：项目不存在或主表/审计落库失败抛异常，事务回滚。</p>
     *
     * @param g 阶段门草稿
     * @return 持久化后的实体（含 code）
     */
    @Transactional
    public StageGate create(StageGate g) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(g.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        Long uid = UserContext.currentUserId();
        g.setId(null);
        g.setCode(codeGenerator.next(project.getId(), project.getCode(), "DCP"));
        if (g.getSeq() == null) {
            g.setSeq(0);
        }
        g.setCreatedBy(uid);
        g.setUpdatedBy(uid);
        g.setCreatedAt(LocalDateTime.now());
        g.setUpdatedAt(LocalDateTime.now());
        g.setDeleted(0);
        mapper.insert(g);
        audit.record(g.getProjectId(), "STAGE_GATE", g.getId(), "CREATE",
                "创建阶段/DCP " + g.getCode() + " " + g.getStageName() + "/" + g.getGateName(), null, g);
        return g;
    }

    /**
     * 按补丁更新阶段门字段（写链路）：
     * <ol>
     *   <li>按 ID 查询旧值，不存在即 4040。</li>
     *   <li>按非空 patch 局部覆盖，避免覆盖空值。</li>
     *   <li>刷新更新人、更新时间后入库。</li>
     *   <li>记录 UPDATE 审计，形成变更闭环。</li>
     * </ol>
     * <p>更新粒度：变更当前阶段门的元数据字段，不影响承诺/执行记录。</p>
     */
    @Transactional
    public StageGate update(Long id, StageGate patch) {
        UserContext.requireRole("PM");
        StageGate old = mapper.selectById(id);
        if (old == null) {
            throw new BusinessException(4040, "阶段/DCP 不存在");
        }
        if (patch.getStageName() != null) old.setStageName(patch.getStageName());
        if (patch.getGateName() != null) old.setGateName(patch.getGateName());
        if (patch.getSeq() != null) old.setSeq(patch.getSeq());
        if (patch.getPlanDate() != null) old.setPlanDate(patch.getPlanDate());
        if (patch.getForecastDate() != null) old.setForecastDate(patch.getForecastDate());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "STAGE_GATE", id, "UPDATE", "更新阶段/DCP " + old.getCode(), null, old);
        return old;
    }
}
