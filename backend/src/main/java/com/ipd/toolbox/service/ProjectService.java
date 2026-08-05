package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectService {

    private final ProjectMapper mapper;
    private final AuditService audit;
    private final ClosureService closureService;

    public ProjectService(ProjectMapper mapper, AuditService audit, ClosureService closureService) {
        this.mapper = mapper;
        this.audit = audit;
        this.closureService = closureService;
    }

    public List<Project> list() {
        return mapper.selectList(new QueryWrapper<Project>().orderByDesc("created_at"));
    }

    public Project get(Long id) {
        Project p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        return p;
    }

    @Transactional
    public Project create(Project p) {
        UserContext.requireRole("PM");
        if (p.getCode() == null || p.getCode().isBlank()) {
            throw new BusinessException("项目代码不能为空");
        }
        Long dup = mapper.selectCount(new QueryWrapper<Project>().eq("code", p.getCode()));
        if (dup != null && dup > 0) {
            throw new BusinessException("项目代码已存在: " + p.getCode());
        }
        Long uid = UserContext.currentUserId();
        p.setId(null);
        p.setLifecycleStatus(p.getLifecycleStatus() == null ? "ACTIVE" : p.getLifecycleStatus());
        p.setCreatedBy(uid);
        p.setUpdatedBy(uid);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        audit.record(p.getId(), "PROJECT", p.getId(), "CREATE",
                "创建项目 " + p.getCode() + " " + p.getName(), null, p);
        return p;
    }

    @Transactional
    public Project update(Long id, Project patch) {
        UserContext.requireRole("PM");
        Project old = get(id);
        Project snapshot = cloneOf(old);
        if (patch.getName() != null) {
            old.setName(patch.getName());
        }
        if (patch.getGoal() != null) {
            old.setGoal(patch.getGoal());
        }
        if (patch.getManagerId() != null) {
            old.setManagerId(patch.getManagerId());
        }
        if (patch.getLifecycleStatus() != null) {
            if (!java.util.Set.of("ACTIVE", "ON_HOLD", "CLOSED").contains(patch.getLifecycleStatus())) {
                throw new BusinessException("无效的项目生命周期状态: " + patch.getLifecycleStatus());
            }
            old.setLifecycleStatus(patch.getLifecycleStatus());
        }
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        // 结项留痕：流转 CLOSED 时把未了事项计数写入审计（不拦截，决定权在人）
        String summary = "更新项目 " + old.getCode();
        if ("CLOSED".equals(patch.getLifecycleStatus())
                && !"CLOSED".equals(snapshot.getLifecycleStatus())) {
            ClosureService.CloseoutCheck c = closureService.check(id);
            summary = "结项 " + old.getCode() + (c.clean() ? "（各项已清零）"
                    : String.format("（未闭合风险 %d、未评审 DCP %d、未关缺陷 %d、在途变更 %d、红线未满足 %d）",
                            c.openRisks(), c.unreviewedGates(), c.openDefects(),
                            c.pendingChanges(), c.unmetRedlines()));
        }
        audit.record(id, "PROJECT", id, "UPDATE", summary, snapshot, old);
        return old;
    }

    private Project cloneOf(Project s) {
        Project c = new Project();
        c.setId(s.getId());
        c.setCode(s.getCode());
        c.setName(s.getName());
        c.setGoal(s.getGoal());
        c.setManagerId(s.getManagerId());
        c.setLifecycleStatus(s.getLifecycleStatus());
        return c;
    }
}
