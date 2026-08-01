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

    public List<Evidence> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Evidence>()
                .eq("project_id", projectId).orderByDesc("id"));
    }

    public Evidence get(Long id) {
        Evidence e = mapper.selectById(id);
        if (e == null) {
            throw new BusinessException(4040, "证据不存在");
        }
        return e;
    }

    @Transactional
    public Evidence upload(Long projectId, MultipartFile file, String linkType, Long linkId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
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
            e.setCode(codeGenerator.next(project.getId(), project.getCode(), "EV"));
            e.setFileName(file.getOriginalFilename());
            e.setFilePath(target.toString());
            e.setSha256(sha256);
            e.setSizeBytes((long) bytes.length);
            e.setMime(file.getContentType());
            e.setUploadedBy(UserContext.currentUserId());
            e.setCreatedAt(LocalDateTime.now());
            e.setDeleted(0);
            mapper.insert(e);
            audit.record(projectId, "EVIDENCE", e.getId(), "CREATE",
                    "上传证据 " + e.getCode() + " " + e.getFileName() + " (sha256=" + sha256.substring(0, 12) + "…)",
                    null, e);

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

    public byte[] readBytes(Long id) {
        Evidence e = get(id);
        try {
            return Files.readAllBytes(Paths.get(e.getFilePath()));
        } catch (IOException ex) {
            throw new BusinessException("证据文件读取失败: " + ex.getMessage());
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new BusinessException("摘要计算失败");
        }
    }
}
