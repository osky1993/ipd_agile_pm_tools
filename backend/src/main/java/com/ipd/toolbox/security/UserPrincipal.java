package com.ipd.toolbox.security;

import java.util.List;

/** 当前登录用户的最小主体信息。 */
public record UserPrincipal(Long userId, String username, List<String> roles) {

    /** 兼容 ADMIN 全局放行；否侧按显式角色匹配。 */
    public boolean hasRole(String role) {
        return roles != null && (roles.contains(role) || roles.contains("ADMIN"));
    }

    /**
     * 判断是否具备任一允许角色，任一匹配即放行（含 ADMIN 全放开）。
     * @param candidates 需要的角色集合
     */
    public boolean hasAnyRole(String... candidates) {
        if (roles == null) {
            return false;
        }
        if (roles.contains("ADMIN")) {
            return true;
        }
        for (String c : candidates) {
            if (roles.contains(c)) {
                return true;
            }
        }
        return false;
    }
}
