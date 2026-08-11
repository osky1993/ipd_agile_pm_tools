package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.TeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/team")
/**
 * 团队协同视角控制器：聚合团队内关键排期、阻塞、交接与负载信息。
 * 为团队面板和日常站会视图提供“可推进性”指标。
 */
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    /** 团队总览：返回需求/阻塞/负责人负载，支持快速识别单点风险。 */
    @GetMapping("/overview")
    public Result<TeamService.Overview> overview(@RequestParam Long projectId) {
        return Result.ok(service.overview(projectId));
    }
}
