package com.github.zhizi01.ets.security;

import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;

/** 当前请求的登录主体，由 AuthInterceptor 写入、请求结束时清理。 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(LoginUser lu) {
        HOLDER.set(lu);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser require() {
        LoginUser lu = HOLDER.get();
        if (lu == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return lu;
    }

    public static String userId() {
        return require().getId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
