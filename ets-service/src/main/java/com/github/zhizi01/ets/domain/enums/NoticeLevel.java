package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 公告级别 */
@Getter
public enum NoticeLevel implements LabeledEnum {

    INFO("普通"),
    WARN("重要"),
    URGENT("紧急");

    private final String label;

    NoticeLevel(String label) {
        this.label = label;
    }
}
