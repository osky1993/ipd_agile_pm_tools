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
public class StageGateService {

    private final StageGateMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    public StageGateService(StageGateMapper mapper, ProjectMapper projectMapper,
                            CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    public List<StageGate> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<StageGate>()
                .eq("project_id", projectId).orderByAsc("seq").orderByAsc("id"));
    }

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
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "STAGE_GATE", id, "UPDATE", "更新阶段/DCP " + old.getCode(), null, old);
        return old;
    }
}
