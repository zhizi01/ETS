package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AdminVO;
import com.github.zhizi01.ets.repo.AdminRepo;
import com.github.zhizi01.ets.security.AuthInterceptor;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端登录态校验与登出，令牌在服务端立即失效。 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminRepo adminRepo;
    private final TokenService tokenService;

    @GetMapping("/me")
    public R<AdminVO> me(@CurrentUser LoginUser me) {
        return R.ok(AdminVO.of(adminRepo.require(me.getId(), ErrorCode.UNAUTHORIZED)));
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        String token = request.getHeader(AuthInterceptor.HEADER);
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");
        }
        if (token != null && !token.isBlank()) {
            tokenService.revoke(token);
        }
        return R.ok();
    }
}
