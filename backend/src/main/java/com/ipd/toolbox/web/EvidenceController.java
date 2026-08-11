package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Evidence;
import com.ipd.toolbox.service.EvidenceService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/evidence")
/**
 * 证据控制器：支持列表/上传/下载/预览。
 * 关键点：
 * - fileName 与 sha256 由服务端统一计算落库
 * - preview 仅放行 image/pdf 的 inline，其余降级到下载
 */
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    /**
     * 查询项目证据列表。
     *
     * <p>用途：为列表页/关联页面返回文档与附件集合，支持按 category 过滤。</p>
     *
     * <p>返回：Evidence 列表；默认按创建时间或服务端规则排序。</p>
     */
    @GetMapping
    public Result<List<Evidence>> list(@RequestParam Long projectId,
                                       @RequestParam(required = false) String category) {
        return Result.ok(service.list(projectId, category));
    }

    /**
     * 上传证据文件。
     *
     * <p>用途：接收 Multipart 文件并入库元信息，支持与任意业务对象建立追溯关联（linkType/linkId）。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>持久化文件元数据（文件名/SHA/大小/MIME）。</li>
     *   <li>可选建立追溯关系用于关系图与评审闭环。</li>
     * </ul>
     *
     * <p>失败场景：
     * <ul>
     *   <li>文件为空或读取失败。</li>
     *   <li>服务层校验文件大小/类型不通过。</li>
     * </ul>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Evidence> upload(@RequestParam Long projectId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String linkType,
                                   @RequestParam(required = false) Long linkId,
                                   @RequestParam(required = false) String category) {
        return Result.ok(service.upload(projectId, file, linkType, linkId, category));
    }

    /**
     * 下载证据文件。
     *
     * <p>用途：返回二进制流，HTTP header 使用 RFC 5987 编码文件名，避免中文乱码。</p>
     *
     * <p>副作用：
     * <ul>
     *   <li>从存储读取原始字节（可能伴随读取计数统计）。</li>
     *   <li>返回 attachment 流量头，触发浏览器下载。</li>
     * </ul>
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable Long id) {
        Evidence e = service.get(id);
        byte[] bytes = service.readBytes(id);
        String filename = URLEncoder.encode(e.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(bytes));
    }

    /**
     * 预览证据文件。
     *
     * <p>用途：对 image/* 与 application/pdf 进行 inline 浏览，其它类型降级为下载。</p>
     *
     * <p>安全边界：
     * <ul>
     *   <li>SVG 等可能带脚本的资源不允许 inline。</li>
     *   <li>统一设置 nosniff，避免部分内容被浏览器按活动 MIME 执行。</li>
     * </ul>
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<ByteArrayResource> preview(@PathVariable Long id) {
        Evidence e = service.get(id);
        String mime = e.getMime() == null ? "" : e.getMime();
        boolean inlineSafe = (mime.startsWith("image/") && !mime.contains("svg"))
                || mime.equals(MediaType.APPLICATION_PDF_VALUE);
        if (!inlineSafe) {
            return download(id);
        }
        byte[] bytes = service.readBytes(id);
        String filename = URLEncoder.encode(e.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + filename)
                // 防止 HTML/SVG 等被浏览器当活动内容执行
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(bytes));
    }
}
