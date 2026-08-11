package com.ipd.toolbox.web;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import com.ipd.toolbox.service.AuditService;
import com.ipd.toolbox.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
/**
 * 认证控制器：登录/续签、会话查询。
 * 接口边界：
 * - /login：用户名密码换取短时 token（配置默认有效期）
 * - /api-token：已登录用户申请 MCP 方案专用长效 token（需要先登录）
 * - /me：返回当前 token 解析出的上下文
 */
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    /**
     * 签发长期 API token。
     *
     * 用途：
     * 为已登录用户签发供 MCP / 脚本对接使用的长有效期 token，支持服务端任务与外部集成。
     *
     * 入参约束：
     * 无请求体参数，直接依赖上下文中的当前登录用户信息。
     *
     * 更新粒度：
     * 产生新的访问凭证并持久化到鉴权体系（若已存在旧 token，具体更新策略由鉴权服务定义，如覆盖或并行有效）。
     * 成功返回后应立即将新 token 覆盖本地缓存，避免并发窗口使用旧凭证。
     *
     * 返回：
     * 返回 token 元数据 map，包括访问密钥及有效期字段（以服务端返回体为准）。
     *
     * 异常与边界：
     * 未登录时抛出 4010；鉴权服务异常会沿 Result/异常模型透出。
     */
    @PostMapping("/api-token")
    public Result<Map<String, Object>> apiToken() {
        Long uid = UserContext.currentUserId();
        if (uid == null) {
            throw new BusinessException(4010, "未登录");
        }
        return Result.ok(authService.apiToken(uid, auditService));
    }

    /** 登录请求参数：用户名 + 密码。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 登录并返回会话信息。
     *
     * 用途：
     * 校验用户名与密码，登录成功后返回 token 与身份信息（供前端 auth store 写入）。
     *
     * 入参约束：
     * username 与 password 均不能为空；非法内容将触发参数校验。
     *
     * 更新粒度：
     * 登录成功后会形成新的会话状态，通常包含 token 颁发与最后登录时间记录；
     * 登录失败不会产生会话变更。
     *
     * 返回：
     * 返回登录成功上下文（token、用户展示信息、角色与权限概要）。
     *
     * 异常与边界：
     * 凭据错误、账号锁定或服务不可用将返回鉴权相关错误码；该接口未显式执行幂等保护。
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.username(), req.password()));
    }

    /**
     * 获取当前会话的用户身份信息。
     *
     * 用途：
     * 前端用于快速探测登录状态与角色范围，避免重复拉取重定向逻辑。
     *
     * 入参约束：
     * 无请求参数，以 token 解析上下文为准；无效 token 会进入异常路径。
     *
     * 返回：
     * 返回当前用户身份对象（含角色与鉴权上下文关键字段）。
     *
     * 异常与边界：
     * 若当前上下文未登录，抛出上下文异常；该接口本身不修改持久化数据。
     */
    @GetMapping("/me")
    public Result<UserPrincipal> me() {
        return Result.ok(UserContext.require());
    }
}
