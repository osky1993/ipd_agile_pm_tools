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

/**
 * 产品版本服务。
 * <p>
 * 负责按项目维护版本号、发布日期等生命周期属性。
 * 所有变更会走 PM 权限校验，并记录审计动作，便于交付质量追溯。
 */
@Service
public class ProductVersionService {

    private final ProductVersionMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    /**
     * 产品版本服务依赖注入。
     * mapper 持久化版本对象，projectMapper 做归属项目校验，
     * codeGenerator 负责版本编码生成，audit 记录版本变更审计。
     */
    public ProductVersionService(ProductVersionMapper mapper, ProjectMapper projectMapper,
                                 CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    /**
     * 按项目查询版本历史。
     * <p>返回值按创建时间倒序，供前端下拉/详情页展示历史版本。该方法不改状态、
     * 不参与写事务，默认按项目维度执行索引查询。</p>
     *
     * @param projectId 项目 ID
     * @return 指定项目的版本列表
     */
    public List<ProductVersion> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<ProductVersion>()
                .eq("project_id", projectId).orderByDesc("created_at"));
    }

    /**
     * 创建一个版本记录。
     * <p>主要职责：校验归属项目存在、版本号不能为空、生成版本编码、补齐元数据并入库，
     * 最后记录 CREATE 审计。
     * 发生空项目、空版本号等非法入参时直接抛业务异常。</p>
     * <p>更新粒度：
     * <ul>
     *   <li>先验证参数与项目存在性；`versionNo` 非空是硬约束。</li>
     *   <li>清理主键并生成 `VER` 编码，补齐创建/更新人和时间。</li>
     *   <li>插入主表并写入 `PRODUCT_VERSION CREATE` 审计。</li>
     * </ul>
     * <p>失败策略：事务内写入失败回滚，未持久化任何版本快照。
     * 已生成 code 仅用于内存对象，未入库则不会外泄。</p>
     * <p>幂等边界：不做重复版本号防重，重复提交会形成并存行，依赖数据库唯一约束或上游幂等。</p>
     *
     * @param v 待创建版本对象（要求至少包含 projectId、versionNo）
     * @return 持久化后的版本实例（含生成后的 code 与主键）
     * @throws BusinessException 当项目不存在或入参非法时抛出
     */
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

    /**
     * 按字段补丁方式更新版本。
     * <p>仅更新 patch 中非空属性，并更新 updatedBy/updatedAt。
     * 采用“先读后改再写回”的模式，便于避免全量覆盖导致意外丢字段。</p>
     * <p>更新粒度：支持 `model/versionNo/baseline/planReleaseDate/actualReleaseDate`；
     * 其它字段（如 project_id/id/code）保持原值，不允许变更。</p>
     * <p>边界与副作用：</p>
     * <ul>
     *   <li>不存在时抛 4040，避免静默创建。</li>
     *   <li>仅更新数据库实际持久化实体；不同步更新任何关联的测试/决策引用。</li>
     *   <li>每次更新记录 `PRODUCT_VERSION UPDATE` 审计。</li>
     * </ul>
     * <p>失败回退：事务回滚；`old` 引用在异常下不会泄漏到外部持久化。</p>
     *
     * @param id    待更新版本 ID
     * @param patch 更新补丁对象
     * @return 更新后的版本对象
     * @throws BusinessException 当版本不存在或缺少权限时抛出
     */
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
        if (patch.getPlanReleaseDate() != null) old.setPlanReleaseDate(patch.getPlanReleaseDate());
        if (patch.getActualReleaseDate() != null) old.setActualReleaseDate(patch.getActualReleaseDate());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "PRODUCT_VERSION", id, "UPDATE", "更新版本 " + old.getCode(), null, old);
        return old;
    }
}
