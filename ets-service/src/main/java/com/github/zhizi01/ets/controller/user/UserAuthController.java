package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AuthPwdReq;
import com.github.zhizi01.ets.domain.dto.UserVO;
import com.github.zhizi01.ets.security.AuthInterceptor;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.security.TokenService;
import com.github.zhizi01.ets.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录态下的账号自助。 */
@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserService userService;
    private final TokenService tokenService;

    @GetMapping("/me")
    public R<UserVO> me(@CurrentUser LoginUser me) {
        return R.ok(UserVO.of(userService.require(me.getId())));
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

    /** 改密后本人的全部会话都会失效，前端需引导重新登录。 */
    @PostMapping("/password")
    public R<Void> changePassword(@CurrentUser LoginUser me, @Valid @RequestBody AuthPwdReq req) {
        userService.changePassword(me.getId(), req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }
}
