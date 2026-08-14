package com.github.zhizi01.ets.controller.pub;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AdminVO;
import com.github.zhizi01.ets.domain.dto.AuthLoginReq;
import com.github.zhizi01.ets.domain.dto.AuthRegisterReq;
import com.github.zhizi01.ets.domain.dto.AuthTokenVO;
import com.github.zhizi01.ets.domain.dto.UserVO;
import com.github.zhizi01.ets.domain.model.Admin;
import com.github.zhizi01.ets.domain.model.User;
import com.github.zhizi01.ets.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 免鉴权的注册与登录入口。响应一律走 VO，绝不携带口令散列。 */
@RestController
@RequestMapping("/api/pub/auth")
@RequiredArgsConstructor
public class PubAuthController {

    private final UserService userService;

    @PostMapping("/register")
    public R<AuthTokenVO> register(@Valid @RequestBody AuthRegisterReq req) {
        User u = userService.register(req);
        // 注册即登录，省掉前端一次跳转
        return R.ok(AuthTokenVO.ofUser(userService.issueToken(u), UserVO.of(u)));
    }

    @PostMapping("/login")
    public R<AuthTokenVO> login(@Valid @RequestBody AuthLoginReq req) {
        User u = userService.login(req.getUsername(), req.getPassword());
        return R.ok(AuthTokenVO.ofUser(userService.issueToken(u), UserVO.of(u)));
    }

    @PostMapping("/admin/login")
    public R<AuthTokenVO> adminLogin(@Valid @RequestBody AuthLoginReq req) {
        Admin a = userService.adminLogin(req.getUsername(), req.getPassword());
        return R.ok(AuthTokenVO.ofAdmin(userService.issueToken(a), AdminVO.of(a)));
    }
}
