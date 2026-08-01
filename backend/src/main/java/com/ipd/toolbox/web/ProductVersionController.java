package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.ProductVersion;
import com.ipd.toolbox.service.ProductVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-versions")
public class ProductVersionController {

    private final ProductVersionService service;

    public ProductVersionController(ProductVersionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<ProductVersion>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    @PostMapping
    public Result<ProductVersion> create(@RequestBody ProductVersion v) {
        return Result.ok(service.create(v));
    }

    @PutMapping("/{id}")
    public Result<ProductVersion> update(@PathVariable Long id, @RequestBody ProductVersion v) {
        return Result.ok(service.update(id, v));
    }
}
