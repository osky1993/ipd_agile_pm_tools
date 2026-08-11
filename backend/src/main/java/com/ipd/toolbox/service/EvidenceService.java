package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Evidence;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.mapper.EvidenceMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 证据服务（T402）：文件落本地目录（按项目分目录），DB 存元数据 + SHA-256 摘要；
 * 可选建立 关联对象 -evidences-> 证据 追溯（证据优先于完成百分比，规划§2.3）。
 */
@Service
public class EvidenceService {

    private final EvidenceMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final TraceLinkService traceLinkService;
    private final String root;

    /**
     * 证据服务依赖注入。
     * 证据元数据落库到 mapper，项目元数据用于编码前校验，
     * codeGenerator 负责 EV 编码，audit 负责 EVIDENCE 创建留痕，traceLinkService 负责追溯挂链。
     */
    public EvidenceService(EvidenceMapper mapper, ProjectMapper projectMapper, CodeGenerator codeGenerator,
                           AuditService audit, TraceLinkService traceLinkService,
                           @Value("${ipd.evidence.root}") String root) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.traceLinkService = traceLinkService;
        this.root = root;
    }

    /**
     * 列出证据。
     *
     * <p>查询只读：按项目过滤并按 ID 倒序返回，默认仅返回 `EVIDENCE` 分类。
     * 当 {@code category=ATTACHMENT} 时返回附件分类，不对分类值做白名单校验，
     * 上层若传入非法值会返回空结果（依赖 DB 条件天然过滤）。</p>
     *
     * @param projectId 项目 ID
     * @param category 证据分类：EVIDENCE/ATTACHMENT；空则默认 EVIDENCE
     */
    public List<Evidence> list(Long projectId, String category) {
        return mapper.selectList(new QueryWrapper<Evidence>()
                .eq("project_id", projectId)
                .eq("category", category == null || category.isBlank() ? "EVIDENCE" : category)
                .orderByDesc("id"));
    }

    /**
     * 列出项目默认证据（category 默认 EVIDENCE）。
     *
     * <p>该重载仅提供 `category = null` 的快捷入口，等价调用 {@link #list(Long, String)}，返回行为稳定可复用。</p>
     */
    public List<Evidence> list(Long projectId) {
        return list(projectId, null);
    }

    /**
     * 获取单条证据元数据。
     *
     * <p>只读按 ID 查询：不存在时抛 {@code BusinessException(4040, ...)}，避免下游空指针。</p>
     *
     * @param id 证据 ID
     */
    public Evidence get(Long id) {
        Evidence e = mapper.selectById(id);
        if (e == null) {
            throw new BusinessException(4040, "证据不存在");
        }
        return e;
    }

    /**
     * 上传到默认存储目录（`ipd.evidence.root/{projectId}`）的便利重载。
     *
     * <p>该重载将 `category` 置空，沿用主 `upload` 的角色校验、落盘与审计逻辑；用于上层默认场景减少参数噪音。</p>
     *
     * @param projectId 项目 ID
     * @param file      上传文件
     * @param linkType  可选，源对象类型
     * @param linkId    可选，源对象 ID
     */
    @Transactional
    public Evidence upload(Long projectId, MultipartFile file, String linkType, Long linkId) {
        return upload(projectId, file, linkType, linkId, null);
    }

    /**
     * 上传文件并落库元数据。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>参数校验：项目必须存在；文件不能为空。</li>
     *   <li>落盘动作：在 {@code ipd.evidence.root/{projectId}} 下创建目录并写入随机名文件，生成 SHA-256 摘要。</li>
     *   <li>元数据落库：插入 {@code EVIDENCE}，并可选生成 {@code EV} 编码；附件类型则改用随机临时码 {@code AT-*}。</li>
     *   <li>审计/追溯：除附件外均写 `EVIDENCE CREATE` 审计；当 {@code linkType/linkId} 存在时补建 evidences 关系。</li>
     *   <li>失败策略：方法为 {@code @Transactional}，运行期异常将回滚数据库，但已落入文件系统的临时文件不会自动清理（当前实现的既定行为）。</li>
     * </ul>
     * <p>失败回退边界：文件入库与 DB 落库同事务，不一致只在 I/O 失败边界出现（文件已写入但元数据未落库）；该场景当前不做异步清理。</p>
     *
     * @param projectId 项目 ID
     * @param file      上传文件
     * @param linkType  可选，源对象类型，存在时将建立 `evidences` 关系
     * @param linkId    可选，源对象 ID
     * @param category  可选，ATTACHMENT 时不生成正式证据编号/审计
     */
    @Transactional
    public Evidence upload(Long projectId, MultipartFile file, String linkType, Long linkId, String category) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        boolean attachment = "ATTACHMENT".equals(category);
        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256(bytes);
            Path dir = Paths.get(root, String.valueOf(projectId));
            Files.createDirectories(dir);
            String stored = UUID.randomUUID().toString().replace("-", "") + "_" + file.getOriginalFilename();
            Path target = dir.resolve(stored);
            Files.write(target, bytes);

            Evidence e = new Evidence();
            e.setProjectId(projectId);
            // 附件不占用 EV 正式编号（也不进证据审计流），用随机短码满足唯一约束
            e.setCode(attachment
                    ? "AT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                    : codeGenerator.next(project.getId(), project.getCode(), "EV"));
            e.setCategory(attachment ? "ATTACHMENT" : "EVIDENCE");
            e.setFileName(file.getOriginalFilename());
            e.setFilePath(target.toString());
            e.setSha256(sha256);
            e.setSizeBytes((long) bytes.length);
            e.setMime(file.getContentType());
            e.setUploadedBy(UserContext.currentUserId());
            e.setCreatedAt(LocalDateTime.now());
            e.setDeleted(0);
            mapper.insert(e);
            if (!attachment) {
                audit.record(projectId, "EVIDENCE", e.getId(), "CREATE",
                        "上传证据 " + e.getCode() + " " + e.getFileName() + " (sha256=" + sha256.substring(0, 12) + "…)",
                        null, e);
            }

            // 关联对象 -evidences-> 证据
            if (linkType != null && linkId != null) {
                TraceLink link = new TraceLink();
                link.setProjectId(projectId);
                link.setSourceType(linkType);
                link.setSourceId(linkId);
                link.setTargetType("EVIDENCE");
                link.setTargetId(e.getId());
                link.setRelation("evidences");
                traceLinkService.create(link);
            }
            return e;
        } catch (IOException ex) {
            throw new BusinessException("文件保存失败: " + ex.getMessage());
        }
    }

    /**
     * 按 id 读取证据二进制。
     *
     * <p>读取策略：严格使用数据库中记录的绝对路径读取文件，文件不存在/读失败时抛业务异常；
     * 返回原始字节流，不做任何二次编码。</p>
     * <p>一致性边界：若文件路径失效（脱库、外部清理），异常会直接向上传播，不返回降级内容。</p>
     */
    public byte[] readBytes(Long id) {
        Evidence e = get(id);
        try {
            return Files.readAllBytes(Paths.get(e.getFilePath()));
        } catch (IOException ex) {
            throw new BusinessException("证据文件读取失败: " + ex.getMessage());
        }
    }

    /**
     * 计算文件 SHA-256 摘要。
     *
     * <p>私有工具方法；失败时统一抛出业务异常，避免调用层继续处理不同异常类型。</p>
     * <p>确定性边界：同一输入字节数组返回同一摘要，用于验真与排错追踪。</p>
     */
    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new BusinessException("摘要计算失败");
        }
    }
}
