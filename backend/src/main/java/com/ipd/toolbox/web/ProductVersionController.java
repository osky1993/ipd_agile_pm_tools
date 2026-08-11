package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.ProductVersion;
import com.ipd.toolbox.service.ProductVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-versions")
/**
 * 版本目录控制器：管理项目下的产品版本基线与投产计划映射。
 * 结合基线/风险窗口形成发布时序和历史回溯基础。
 */
public class ProductVersionController {

    private final ProductVersionService service;

    public ProductVersionController(ProductVersionService service) {
        this.service = service;
    }

    /** 查询项目版本列表（按时间倒序，供版本树、基线页和发布计划）。 */
    @GetMapping
    public Result<List<ProductVersion>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }

    /** 创建版本条目：用于追踪需求归属和交付窗口。 */
    @PostMapping
    public Result<ProductVersion> create(@RequestBody ProductVersion v) {
        return Result.ok(service.create(v));
    }

    /** 修改版本字段：适配版本冻结前的数据校准。 */
    @PutMapping("/{id}")
    public Result<ProductVersion> update(@PathVariable Long id, @RequestBody ProductVersion v) {
        return Result.ok(service.update(id, v));
    }
}
