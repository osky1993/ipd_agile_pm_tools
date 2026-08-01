package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.ProductVersion;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.ProductVersionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductVersionService {

    private final ProductVersionMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    public ProductVersionService(ProductVersionMapper mapper, ProjectMapper projectMapper,
                                 CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    public List<ProductVersion> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<ProductVersion>()
                .eq("project_id", projectId).orderByDesc("created_at"));
    }

    @Transactional
    public ProductVersion create(ProductVersion v) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(v.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (v.getVersionNo() == null || v.getVersionNo().isBlank()) {
            throw new BusinessException("版本号不能为空");
        }
        Long uid = UserContext.currentUserId();
        v.setId(null);
        v.setCode(codeGenerator.next(project.getId(), project.getCode(), "VER"));
        v.setCreatedBy(uid);
        v.setUpdatedBy(uid);
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        v.setDeleted(0);
        mapper.insert(v);
        audit.record(v.getProjectId(), "PRODUCT_VERSION", v.getId(), "CREATE",
                "创建产品版本 " + v.getCode() + " " + v.getVersionNo(), null, v);
        return v;
    }

    @Transactional
    public ProductVersion update(Long id, ProductVersion patch) {
        UserContext.requireRole("PM");
        ProductVersion old = mapper.selectById(id);
        if (old == null) {
            throw new BusinessException(4040, "版本不存在");
        }
        if (patch.getModel() != null) old.setModel(patch.getModel());
        if (patch.getVersionNo() != null) old.setVersionNo(patch.getVersionNo());
        if (patch.getBaseline() != null) old.setBaseline(patch.getBaseline());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "PRODUCT_VERSION", id, "UPDATE", "更新版本 " + old.getCode(), null, old);
        return old;
    }
}
