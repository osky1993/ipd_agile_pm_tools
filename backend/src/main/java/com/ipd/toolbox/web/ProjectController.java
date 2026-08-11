package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.service.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
/**
 * 项目控制器：项目维度的读取/更新，以及结项检查和周报快照。
 * - 核心写入接口保持幂等性前提下做最小字段覆盖
 * - 读口径默认服务端权限通过 UserContext 在 service 内部验权
 */
public class ProjectController {

    private final ProjectService service;
    private final com.ipd.toolbox.service.WeeklyReportService weeklyReportService;
    private final com.ipd.toolbox.service.ClosureService closureService;

    public ProjectController(ProjectService service,
                             com.ipd.toolbox.service.WeeklyReportService weeklyReportService,
                             com.ipd.toolbox.service.ClosureService closureService) {
        this.service = service;
        this.weeklyReportService = weeklyReportService;
        this.closureService = closureService;
    }

    /**
     * 结项前置条件检查。
     *
     * <p>用途：聚合项目信息完整性（如证据、决策、风险闭环等）并返回给调用方；方法本身不直接阻断更新流程。
     *
     * <p>返回：CloseoutCheck，包含通过率、缺口计数与建议项列表。
     * <p>副作用：只读。失败/未满足条件不改变项目状态，由上层决定是否继续执行 close 操作。
     */
    @GetMapping("/{id}/closeout-check")
    public Result<com.ipd.toolbox.service.ClosureService.CloseoutCheck> closeoutCheck(@PathVariable Long id) {
        return Result.ok(closureService.check(id));
    }

    /**
     * 查询项目周报快照。
     *
     * <p>用途：按时间窗聚合新增项、状态流转、决策与证据信息，用于周报页面与消息摘要。
     *
     * <p>参数：
     * <ul>
     *   <li>id：项目 ID</li>
     *   <li>days：回看天数，默认 7。</li>
     * </ul>
     *
     * <p>边界：请求天窗应在服务端约束范围内，超限由服务层裁剪或返回错误码。
     * <p>副作用：无（只读）。
     */
    @GetMapping("/{id}/weekly")
    public Result<com.ipd.toolbox.service.WeeklyReportService.Summary> weekly(
            @PathVariable Long id, @RequestParam(defaultValue = "7") int days) {
        return Result.ok(weeklyReportService.summary(id, days));
    }

    /**
     * 查询项目台账列表。
     *
     * <p>用途：系统级项目下拉与导航首页数据源。</p>
     * <p>副作用：只读。</p>
     */
    @GetMapping
    public Result<List<Project>> list() {
        return Result.ok(service.list());
    }

    /**
     * 查询单个项目信息。
     *
     * <p>用途：驾驶舱、侧边栏与详情页获取主数据。</p>
     *
     * @param id 项目 ID
     * @return 项目实体；不存在时返回统一错误码
     * <p>副作用：仅读。
     */
    @GetMapping("/{id}")
    public Result<Project> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /**
     * 新建项目。
     *
     * <p>用途：初始化项目主数据（编码/生命周期状态等统一归一化）。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>插入一条 Project 记录。</li>
     *   <li>服务层补齐默认属性与校验业务码合法性。</li>
     * </ul>
     * <p>失败时返回错误码，不应产生孤儿项目。
     */
    @PostMapping
    public Result<Project> create(@RequestBody Project project) {
        return Result.ok(service.create(project));
    }

    /**
     * 更新项目。
     *
     * <p>用途：更新生命周期/属性字段；配合 closeout 检查更新进度提示。</p>
     *
     * <p>更新粒度：
     * <ul>
     *   <li>按请求实体字段更新项目。</li>
     *   <li>常见于更改目标、状态、管理配置等。</li>
     * </ul>
     *
     * <p>副作用：写库更新；建议调用方避免并发重复提交，或保证幂等参数（如版本号）避免覆盖。
     */
    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable Long id, @RequestBody Project project) {
        return Result.ok(service.update(id, project));
    }
}
