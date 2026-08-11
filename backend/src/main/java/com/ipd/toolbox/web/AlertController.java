package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
/**
 * 告警控制器：返回项目的高/中/低优先级告警列表。
 * 对外职责是将告警面板可直接消费的事件清单标准化，服务领导驾驶舱与工作台提醒。
 */
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    /**
     * 按项目返回告警事件列表。
     *
     * 用途：
     * 按 projectId 读取当前项目级告警，返回给提醒弹窗、首页侧边栏和驾驶舱告警卡片。
     *
     * 入参约束：
     * projectId 必须是当前登录上下文可访问的有效项目主键。
     *
     * 返回：
     * 返回项目告警明细集合（按服务层实现排序），无告警时返回空列表。
     *
     * 异常与边界：
     * 项目不存在、无权限时由服务层返回业务异常；返回结构不承诺固定顺序，依赖服务层默认排序。
     */
    @GetMapping
    public Result<List<AlertService.Alert>> list(@RequestParam Long projectId) {
        return Result.ok(service.list(projectId));
    }
}
