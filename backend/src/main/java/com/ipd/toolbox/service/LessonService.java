package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Lesson;
import com.ipd.toolbox.mapper.LessonMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 经验教训（组织资产）：登记/删除/跨项目检索。 */
@Service
public class LessonService {

    static final Set<String> CATEGORIES = Set.of("WELL", "IMPROVE", "PROCESS", "TECH", "SUPPLY", "OTHER");

    private final LessonMapper mapper;
    private final AuditService audit;

    public LessonService(LessonMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    /** 跨项目检索：projectId/category 可选过滤，keyword LIKE title/detail，最多 100 条。 */
    public List<Lesson> search(String keyword, String category, Long projectId) {
        QueryWrapper<Lesson> qw = new QueryWrapper<>();
        if (projectId != null) {
            qw.eq("project_id", projectId);
        }
        if (category != null && !category.isBlank()) {
            qw.eq("category", category);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("title", kw).or().like("detail", kw));
        }
        qw.orderByDesc("id").last("LIMIT 100");
        return mapper.selectList(qw);
    }

    /**
     * 创建经验教训条目。
     *
     * <p>校验字段完整性与枚举类别后落库，并补齐创建人、创建时间与逻辑删除标识；
     * 成功后写入审计事件，便于知识资产变更追溯。</p>
     *
     * @param l 待创建条目
     * @return 持久化后的实体（含 code/id）
     */
    @Transactional
    public Lesson create(Lesson l) {
        if (l.getTitle() == null || l.getTitle().isBlank()) {
            throw new BusinessException("标题不能为空");
        }
        if (l.getCategory() == null || !CATEGORIES.contains(l.getCategory())) {
            throw new BusinessException("类别须为 " + Labels.options(CATEGORIES, Labels::lessonCategory) + " 之一");
        }
        if (l.getProjectId() == null) {
            throw new BusinessException("须指定项目");
        }
        l.setId(null);
        l.setCreatedBy(UserContext.currentUserId());
        l.setCreatedAt(LocalDateTime.now());
        l.setDeleted(0);
        mapper.insert(l);
        audit.record(l.getProjectId(), "LESSON", l.getId(), "CREATE",
                "登记经验教训 [" + Labels.lessonCategory(l.getCategory()) + "] " + l.getTitle(), null, null);
        return l;
    }

    /**
     * 删除经验教训条目（硬删）。
     * 先读后删，避免静默失败并保留审计链路完整性。
     *
     * @param id 经验教训 ID
     */
    @Transactional
    public void delete(Long id) {
        Lesson l = mapper.selectById(id);
        if (l == null) {
            throw new BusinessException(4040, "经验教训不存在");
        }
        mapper.deleteById(id);
        audit.record(l.getProjectId(), "LESSON", id, "DELETE", "删除经验教训 " + l.getTitle(), null, null);
    }
}
