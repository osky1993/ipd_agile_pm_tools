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

    public ProjectService(ProjectMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
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
            old.setLifecycleStatus(patch.getLifecycleStatus());
        }
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(id, "PROJECT", id, "UPDATE", "更新项目 " + old.getCode(), snapshot, old);
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
