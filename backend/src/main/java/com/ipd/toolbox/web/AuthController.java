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
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    /** 签发 365 天长效 API token（需已登录；供 MCP / 脚本对接）。 */
    @PostMapping("/api-token")
    public Result<Map<String, Object>> apiToken() {
        Long uid = UserContext.currentUserId();
        if (uid == null) {
            throw new BusinessException(4010, "未登录");
        }
        return Result.ok(authService.apiToken(uid, auditService));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public Result<UserPrincipal> me() {
        return Result.ok(UserContext.require());
    }
}
