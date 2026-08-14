package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.Admin;
import lombok.Data;

/** 管理员视图，同样用于剥离口令散列。 */
@Data
public class AdminVO {

    private String id;
    private String username;
    private String nickname;
    private boolean superAdmin;
    private long createdAt;
    private long lastLoginAt;

    public static AdminVO of(Admin a) {
        if (a == null) {
            return null;
        }
        AdminVO v = new AdminVO();
        v.id = a.getId();
        v.username = a.getUsername();
        v.nickname = a.getNickname();
        v.superAdmin = a.isSuperAdmin();
        v.createdAt = a.getCreatedAt();
        v.lastLoginAt = a.getLastLoginAt();
        return v;
    }
}
