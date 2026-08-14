package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 登录主体角色 */
@Getter
public enum Role implements LabeledEnum {

    USER("交易用户"),
    ADMIN("管理员");

    private final String label;

    Role(String label) {
        this.label = label;
    }
}
