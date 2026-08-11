package com.ipd.toolbox.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
/**
 * JWT 生命周期管理服务。  
 * 使用对称签名 HMAC-SHA key 进行签发和解析，所有 token 都会携带：
 * - sub：用户 ID
 * - username：登录名
 * - roles：角色列表
 */
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(@Value("${ipd.jwt.secret}") String secret,
                      @Value("${ipd.jwt.expire-minutes}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    /**
     * 使用配置中的默认有效期签发 token。
     * @see #generate(Long, String, List, long)
     */
    public String generate(Long userId, String username, List<String> roles) {
        return generate(userId, username, roles, expireMillis);
    }

    /**
     * 指定有效期签发（用于 MCP 长效 token 等场景）。
     * @param ttlMillis token 过期毫秒数
     */
    public String generate(Long userId, String username, List<String> roles, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 为登录主体；任何签名失效/过期/结构错误均会抛异常。
     * 调用方（过滤器）统一兜底为“未登录”处理。
     */
    @SuppressWarnings("unchecked")
    public UserPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        List<String> roles = claims.get("roles", List.class);
        return new UserPrincipal(userId, username, roles);
    }
}
