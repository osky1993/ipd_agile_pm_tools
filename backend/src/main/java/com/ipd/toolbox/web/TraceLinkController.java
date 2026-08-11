package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.service.TraceLinkService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/traces")
/**
 * 追溯关系控制器：维护工作项、需求、缺陷、测试间的有向关系。
 * 关系将用于风控指标中的追溯完整性、变更影响链等计算。
 */
public class TraceLinkController {

    private final TraceLinkService service;

    public TraceLinkController(TraceLinkService service) {
        this.service = service;
    }

    /**
     * 创建追溯关系边。
     *
     * <p>用途：建立 sourceType/sourceId 到 targetType/targetId 的有向关系（如需求-测试、工作项-证据）。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>新增一条追溯边记录。</li>
     *   <li>关系类型（relation）及类型枚举由服务层校验。</li>
     * </ul>
     *
     * <p>失败边界：非法类型组合/重复边将被拒绝，返回错误码，关系不应部分落库。
     */
    @PostMapping
    public Result<TraceLink> create(@RequestBody TraceLink link) {
        return Result.ok(service.create(link));
    }

    /**
     * 删除追溯关系边。
     *
     * <p>用途：取消一次关系映射，影响后续追溯完整性计算和关联图展示。</p>
     *
     * <p>副作用：
     * <ul>
     *   <li>软删除/硬删除取决于服务实现。</li>
     *   <li>建议保留变更审计快照以支持恢复和追责。</li>
     * </ul>
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
