package com.github.zhizi01.ets.domain.dto;

import lombok.Data;

/**
 * 登录响应。用户登录填 user，管理员登录填 admin，
 * 全局 non_null 序列化策略会自动省略另一个字段。
 */
@Data
public class AuthTokenVO {

    private String token;
    private UserVO user;
    private AdminVO admin;

    public static AuthTokenVO ofUser(String token, UserVO user) {
        AuthTokenVO v = new AuthTokenVO();
        v.token = token;
        v.user = user;
        return v;
    }

    public static AuthTokenVO ofAdmin(String token, AdminVO admin) {
        AuthTokenVO v = new AuthTokenVO();
        v.token = token;
        v.admin = admin;
        return v;
    }
}
