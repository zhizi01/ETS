package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户注册请求，对应 POST /api/pub/auth/register。 */
@Data
public class AuthRegisterReq {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
    private String password;

    @Size(max = 32, message = "昵称过长")
    private String nickname;

    @Size(max = 64, message = "单位名称过长")
    private String orgName;

    @Size(max = 20, message = "手机号过长")
    private String phone;
}
