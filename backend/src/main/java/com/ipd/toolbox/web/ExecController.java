package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.ExecService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exec")
/**
 * 全局运营总览控制器。
 * 返回横向汇总指标，服务首页驾驶舱与数据大屏。
 */
public class ExecController {

    private final ExecService service;

    public ExecController(ExecService service) {
        this.service = service;
    }

    /**
     * 返回全局运营总览。
     *
     * 用途：
     * 聚合项目与运营维度指标，供系统级运营视图展示系统健康与工作负载。
     *
     * 入参约束：
     * 无参数。若前端需要按项目或时间窗筛选应改用下游分页/明细接口。
     *
     * 返回：
     * 返回 Overview 汇总模型，通常包含关键指标与趋势快照。
     *
     * 异常与边界：
     * 为空时返回空指标字段，错误来源（数据库超时、聚合链路异常）由服务层透传。
     */
    @GetMapping("/overview")
    public Result<ExecService.Overview> overview() {
        return Result.ok(service.overview());
    }
}
