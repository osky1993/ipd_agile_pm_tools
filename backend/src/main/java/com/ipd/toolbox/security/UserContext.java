package com.ipd.toolbox.security;

import com.ipd.toolbox.common.BusinessException;

/** 请求线程内的当前用户上下文，由 JwtAuthFilter 写入、拦截器清理。 */
public final class UserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    public static UserPrincipal get() {
        return HOLDER.get();
    }

    public static UserPrincipal require() {
        UserPrincipal p = HOLDER.get();
        if (p == null) {
            throw new BusinessException(4010, "未登录");
        }
        return p;
    }

    public static Long currentUserId() {
        UserPrincipal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static void requireRole(String... roles) {
        UserPrincipal p = require();
        if (!p.hasAnyRole(roles)) {
            throw new BusinessException(4030, "无权限：需要角色 " + String.join("/", roles));
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
