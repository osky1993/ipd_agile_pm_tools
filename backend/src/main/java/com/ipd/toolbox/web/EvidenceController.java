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
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Evidence>> list(@RequestParam Long projectId,
                                       @RequestParam(required = false) String category) {
        return Result.ok(service.list(projectId, category));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Evidence> upload(@RequestParam Long projectId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String linkType,
                                   @RequestParam(required = false) Long linkId,
                                   @RequestParam(required = false) String category) {
        return Result.ok(service.upload(projectId, file, linkType, linkId, category));
    }

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

    /** 内联预览（T708 反馈④）：仅图片/PDF 按真实 MIME inline 返回，其余类型仍走下载。 */
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
