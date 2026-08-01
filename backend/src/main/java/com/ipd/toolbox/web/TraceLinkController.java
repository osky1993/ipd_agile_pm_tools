package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.service.TraceLinkService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/traces")
public class TraceLinkController {

    private final TraceLinkService service;

    public TraceLinkController(TraceLinkService service) {
        this.service = service;
    }

    @PostMapping
    public Result<TraceLink> create(@RequestBody TraceLink link) {
        return Result.ok(service.create(link));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
