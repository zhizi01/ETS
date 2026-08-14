package com.github.zhizi01.ets.domain.model;

import lombok.Data;

@Data
public class Admin {

    private String id;
    private String username;
    private String password;
    private String nickname;
    private boolean superAdmin;
    private long createdAt;
    private long lastLoginAt;
}
