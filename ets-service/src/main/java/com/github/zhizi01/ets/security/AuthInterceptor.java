package com.github.zhizi01.ets.security;

import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.domain.enums.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 依据请求路径判定所需角色：
 * /api/user/**  需要 USER，/api/admin/** 需要 ADMIN，/api/pub/** 放行。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Token";

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String token = req.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            token = req.getParameter("token");
        }
        LoginUser lu = tokenService.resolve(token);
        if (lu == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        String uri = req.getRequestURI();
        if (uri.startsWith("/api/admin/") && lu.getRole() != Role.ADMIN) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅管理员可访问");
        }
        if (uri.startsWith("/api/user/") && lu.getRole() != Role.USER) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅交易用户可访问");
        }

        AuthContext.set(lu);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
