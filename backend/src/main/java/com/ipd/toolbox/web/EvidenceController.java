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
    public Result<List<Evidence>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Evidence> upload(@RequestParam Long projectId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String linkType,
                                   @RequestParam(required = false) Long linkId) {
        return Result.ok(service.upload(projectId, file, linkType, linkId));
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
}
