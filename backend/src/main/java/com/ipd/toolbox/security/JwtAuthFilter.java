package com.ipd.toolbox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 简单 JWT 认证过滤器：解析 Authorization: Bearer <token>，写入 UserContext。
 * 不拦截放行路径；鉴权由各接口通过 UserContext.requireRole 显式判断（简单 RBAC）。
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 每次请求执行一次：
     * 1) 读取 Authorization Header
     * 2) 解析成功则写入 UserContext
     * 3) 最终清理 ThreadLocal，避免线程复用带来的越权泄漏
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                UserContext.set(jwtService.parse(token));
            } catch (Exception ignored) {
                // token 无效则视为未登录，交由接口的 requireRole/require 兜底
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
