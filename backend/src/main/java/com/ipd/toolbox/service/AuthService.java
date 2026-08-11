package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.SysUser;
import com.ipd.toolbox.mapper.SysUserMapper;
import com.ipd.toolbox.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 认证服务。
 * <p>
 * 该服务仅负责三件事：
 * 1. 校验用户凭据；
 * 2. 组装用户角色；
 * 3. 生成并返回可用于前端或 MCP 集成的 JWT Token。
 * <p>
 * 设计上不承担会话状态管理（stateless）和网关鉴权逻辑，
 * 这些职责在过滤链与控制层之间完成。
 */
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 认证能力依赖注入。
     * userMapper 负责校验用户状态与角色查询，passwordEncoder 负责密码比对，jwtService 负责 Token 生成。
     * 该构造函数不承担任何权限或会话副作用，只做基础能力装配。
     */
    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 为指定用户签发 365 天长效 API Token。
     *
     * <p>方法会先校验用户是否存在且未停用，再读取角色列表并交由 JwtService 生成 token，
     * 同时写入审计日志，保证 token 发放行为可追踪。</p>
     *
     * @param userId 用户 ID
     * @param audit  审计服务实例（必须外部透传以便和调用方事务语义解耦）
     * @return 包含 token 与 expiresInDays 的结果映射
     * @throws BusinessException 当用户不存在或账号被停用时抛出
     */
    public Map<String, Object> apiToken(Long userId, AuditService audit) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getEnabled() == null || user.getEnabled() != 1) {
            throw new BusinessException(4011, "用户不存在或已停用");
        }
        List<String> roles = userMapper.findRoleCodes(user.getId());
        long ttl = 365L * 24 * 60 * 60_000L;
        String token = jwtService.generate(user.getId(), user.getUsername(), roles, ttl);
        audit.record(null, "SYS_USER", user.getId(), "API_TOKEN",
                "签发长效 API token（365 天，用户 " + user.getUsername() + "）", null, null);
        return Map.of("token", token, "expiresInDays", 365);
    }

    /**
     * 执行用户登录并返回短期会话凭证。
     *
     * <p>流程包括：按用户名查用户、校验密码、角色口径提取、签发 Token。
     * 返回值中包含了前端初始化会话所需的基础用户信息。</p>
     *
     * @param username 登录名
     * @param password 明文密码
     * @return token、用户基本信息与角色列表
     * @throws BusinessException 用户不存在、已停用或密码错误时抛出
     */
    public Map<String, Object> login(String username, String password) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (user == null || user.getEnabled() == null || user.getEnabled() != 1) {
            throw new BusinessException(4011, "用户不存在或已停用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(4011, "用户名或密码错误");
        }
        List<String> roles = userMapper.findRoleCodes(user.getId());
        String token = jwtService.generate(user.getId(), user.getUsername(), roles);
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "roles", roles
        );
    }
}
