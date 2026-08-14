package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.Role;
import com.github.zhizi01.ets.domain.model.User;
import lombok.Data;

/**
 * 用户视图。存在的唯一理由是隔离 {@link User#getPassword()}，
 * 任何对外响应都必须经过这里，绝不能直接回传实体。
 */
@Data
public class UserVO {

    private String id;
    private String username;
    private String nickname;
    private String orgName;
    private String phone;
    private Role role;
    private String roleLabel;
    private boolean frozen;
    private long createdAt;
    private long lastLoginAt;

    /** 该用户在各模拟盘下已创建的交易账户总数，管理端列表用 */
    private Integer accountCount;

    public static UserVO of(User u) {
        if (u == null) {
            return null;
        }
        UserVO v = new UserVO();
        v.id = u.getId();
        v.username = u.getUsername();
        v.nickname = u.getNickname();
        v.orgName = u.getOrgName();
        v.phone = u.getPhone();
        v.role = u.getRole();
        v.roleLabel = u.getRole() == null ? null : u.getRole().getLabel();
        v.frozen = u.isFrozen();
        v.createdAt = u.getCreatedAt();
        v.lastLoginAt = u.getLastLoginAt();
        return v;
    }
}
