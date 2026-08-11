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
/**
 * 项目服务：负责项目生命周期数据的基础 CRUD 与项目级元信息闭环。
 * 重点规则：
 * - 项目代码必须唯一
 * - 生命周期变更记录审计
 * - 结项时给出未清项提示，但不直接中断保存
 */
public class ProjectService {

    private final ProjectMapper mapper;
    private final AuditService audit;
    private final ClosureService closureService;

    /**
     * 依赖注入主干服务。
     * mapper 负责项目主数据，audit 负责变更审计，closureService 用于结项时的清单提示。
     * 只在写入路径调用 closureService，避免查询路径额外触发结项计算开销。
     */
    public ProjectService(ProjectMapper mapper, AuditService audit, ClosureService closureService) {
        this.mapper = mapper;
        this.audit = audit;
        this.closureService = closureService;
    }

    /**
     * 按创建时间倒序查询项目列表（只读）。
     *
     * <p>不引入过滤条件，保持上游权限和可见性控制一致；
     * 仅决定展示顺序，便于侧边栏、下拉和列表场景复用。</p>
     *
     * @return 项目列表
     */
    public List<Project> list() {
        return mapper.selectList(new QueryWrapper<Project>().orderByDesc("created_at"));
    }

    /**
     * 按主键读取项目（只读）：
     * <ul>
     *   <li>不存在时抛 4040，统一形成可预期错误语义。</li>
     *   <li>返回实体用于上游方法写入前置校验与差异展示。</li>
     * </ul>
     *
     * @param id 项目 ID
     * @return 项目实体
     * @throws BusinessException 未命中时抛 4040
     */
    public Project get(Long id) {
        Project p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        return p;
    }

    /**
     * 创建项目（写链路）：
     * <ol>
     *   <li>校验 code 非空且全局唯一。</li>
     *   <li>补齐默认生命周期与时间戳/操作者字段。</li>
     *   <li>入库后写审计 CREATE 留痕。</li>
     * </ol>
     * <p>更新粒度：该方法只在数据库中新增一条主表记录，更新影响范围是“项目主数据 + 审计日志”。</p>
     * <p>失败策略：事务内任一异常导致插入回滚，避免出现“插入成功但审计缺失”状态。</p>
     *
     * @param p 项目草稿
     * @return 持久化后项目实体
     * @throws BusinessException code 缺失或重复时抛异常
     */
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

    /**
     * 更新项目（写链路）：
     * <ol>
     *   <li>读取旧值形成审计基线。</li>
     *   <li>按补丁语义仅覆盖非空字段。</li>
     *   <li>生命周期只允许固定枚举值；CLOSED 阶段补充结项摘要。</li>
     *   <li>写回主表并写 UPDATE 审计（含新旧快照）。</li>
     * </ol>
     * <p>更新粒度：影响项目关键展示字段与生命周期字段，不做删除和关联级联更新。</p>
     * <p>失败策略：非法状态或主表更新失败抛异常；已更新字段会被事务回滚。</p>
     *
     * @param id 项目 ID
     * @param patch 补丁对象（仅处理非空字段）
     * @return 更新后的实体
     * @throws BusinessException 非法生命周期或项目不存在
     */
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

    /**
     * 生成项目快照副本：用于 UPDATE 审计前后对比。
     * 仅复制关键展示字段，避免审计对象膨胀导致差异阅读困难。
     */
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
