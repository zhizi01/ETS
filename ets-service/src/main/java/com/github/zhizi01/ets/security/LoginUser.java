package com.github.zhizi01.ets.security;

import com.github.zhizi01.ets.domain.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 令牌对应的登录主体，序列化后存于 ets:token:{token}。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private String id;
    private String username;
    private String nickname;
    private Role role;
    private long loginAt;

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
