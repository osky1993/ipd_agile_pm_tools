package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.IterationMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.statemachine.GuardException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class IterationService {

    private final IterationMapper mapper;
    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    public IterationService(IterationMapper mapper, ProjectMapper projectMapper,
                            WorkItemMapper workItemMapper, CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    public List<Iteration> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", projectId).orderByDesc("id"));
    }

    @Transactional
    public Iteration create(Iteration it) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(it.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        Long uid = UserContext.currentUserId();
        it.setId(null);
        it.setCode(codeGenerator.next(project.getId(), project.getCode(), "SPR"));
        if (it.getStatus() == null) it.setStatus("PLANNING");
        it.setCreatedBy(uid);
        it.setUpdatedBy(uid);
        it.setCreatedAt(LocalDateTime.now());
        it.setUpdatedAt(LocalDateTime.now());
        it.setDeleted(0);
        mapper.insert(it);
        audit.record(it.getProjectId(), "ITERATION", it.getId(), "CREATE",
                "创建迭代 " + it.getCode() + " " + it.getName(), null, it);
        return it;
    }

    @Transactional
    public Iteration update(Long id, Iteration patch) {
        UserContext.requireRole("PM");
        Iteration old = mapper.selectById(id);
        if (old == null) {
            throw new BusinessException(4040, "迭代不存在");
        }
        if (patch.getName() != null) old.setName(patch.getName());
        if (patch.getGoal() != null) old.setGoal(patch.getGoal());
        if (patch.getStartDate() != null) old.setStartDate(patch.getStartDate());
        if (patch.getEndDate() != null) old.setEndDate(patch.getEndDate());
        if (patch.getStatus() != null) old.setStatus(patch.getStatus());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "ITERATION", id, "UPDATE", "更新迭代 " + old.getCode(), null, old);
        return old;
    }

    public List<WorkItem> items(Long iterationId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("iteration_id", iterationId).orderByAsc("id"));
    }

    /**
     * 将工作项拉入迭代承诺。守卫#1 Ready：未满足验收条件/责任人/估算的工作项不能进入 Sprint 承诺（T303）。
     */
    @Transactional
    public void assign(Long iterationId, Long workItemId) {
        Iteration it = mapper.selectById(iterationId);
        if (it == null) {
            throw new BusinessException(4040, "迭代不存在");
        }
        WorkItem wi = workItemMapper.selectById(workItemId);
        if (wi == null) {
            throw new BusinessException(4040, "工作项不存在");
        }
        if (isBlank(wi.getAcceptanceCriteria()) || wi.getOwnerId() == null || isBlank(wi.getEstimate())) {
            throw new GuardException("GUARD_READY_UNMET",
                    "进入 Sprint 承诺前需满足 Ready 条件（验收条件、责任人、估算）");
        }
        wi.setIterationId(iterationId);
        wi.setUpdatedBy(UserContext.currentUserId());
        wi.setUpdatedAt(LocalDateTime.now());
        workItemMapper.updateById(wi);
        audit.record(wi.getProjectId(), "WORK_ITEM", workItemId, "UPDATE",
                wi.getCode() + " 拉入迭代 " + it.getCode(), null, null);
    }

    @Transactional
    public void remove(Long workItemId) {
        WorkItem wi = workItemMapper.selectById(workItemId);
        if (wi == null) {
            return;
        }
        // null 字段经 updateById 会被忽略，改用 UpdateWrapper 强制置空 iteration_id
        workItemMapper.update(null, new UpdateWrapper<WorkItem>()
                .eq("id", workItemId)
                .set("iteration_id", null)
                .set("updated_by", UserContext.currentUserId())
                .set("updated_at", LocalDateTime.now()));
        audit.record(wi.getProjectId(), "WORK_ITEM", workItemId, "UPDATE",
                wi.getCode() + " 移出迭代", null, null);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
