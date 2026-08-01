package com.ipd.toolbox.security;

import java.util.List;

/** 当前登录用户的最小主体信息。 */
public record UserPrincipal(Long userId, String username, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && (roles.contains(role) || roles.contains("ADMIN"));
    }

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
