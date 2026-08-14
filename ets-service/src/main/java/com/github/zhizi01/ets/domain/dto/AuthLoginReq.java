package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求，用户端与管理端共用。 */
@Data
public class AuthLoginReq {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
