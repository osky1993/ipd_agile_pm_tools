package com.ipd.toolbox.security;

import com.ipd.toolbox.common.BusinessException;

/** 请求线程内的当前用户上下文，由 JwtAuthFilter 写入、拦截器清理。 */
public final class UserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 在当前请求线程写入用户上下文。
     *
     * 用途：
     * 由鉴权链（例如 JwtAuthFilter）在认证通过后设置上下文，供后续业务代码读取当前用户。
     *
     * 入参约束：
     * principal 不能为空；空值应交由 clear() 或过滤器清理，不建议在业务层显式传入 null。
     *
     * 副作用：
     * 仅影响当前线程内的 ThreadLocal，不会跨线程共享。重复 set 会覆盖同一线程的旧上下文。
     */
    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    /** 读取当前登录上下文（可能为空，不抛异常）。 */
    public static UserPrincipal get() {
        return HOLDER.get();
    }

    /**
     * 读取当前上下文，不存在则直接抛出 4010。
     * 所有必须登录的服务方法应调用此方法。
     */
    public static UserPrincipal require() {
        UserPrincipal p = HOLDER.get();
        if (p == null) {
            throw new BusinessException(4010, "未登录");
        }
        return p;
    }

    /** 仅返回当前用户 ID，未登录时返回 null（适配部分可选归属场景）。 */
    public static Long currentUserId() {
        UserPrincipal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    /** 角色白名单校验，不通过抛 4030。 */
    public static void requireRole(String... roles) {
        UserPrincipal p = require();
        if (!p.hasAnyRole(roles)) {
            throw new BusinessException(4030, "无权限：需要角色 " + String.join("/", roles));
        }
    }

    /**
     * 清理当前请求线程上下文。
     *
     * 用途：
     * 在请求结束或鉴权链失败后移除 ThreadLocal，防止线程复用导致上下文泄露。
     *
     * 幂等性：
     * remove 是幂等的；重复 clear 不产生副作用，但应配合 try/finally 使用。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
