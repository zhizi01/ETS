package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.Role;
import lombok.Data;

@Data
public class User {

    private String id;
    private String username;
    /** 存储的是加盐哈希，绝不回传前端 */
    private String password;
    private String nickname;
    private String orgName;
    private String phone;
    private Role role = Role.USER;
    private boolean frozen;
    private long createdAt;
    private long lastLoginAt;
}
