package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 挂牌状态 */
@Getter
public enum ListingStatus implements LabeledEnum {

    LISTED("挂牌中"),
    PARTIAL_TAKEN("部分摘牌"),
    FULLY_TAKEN("全部摘牌"),
    WITHDRAWN("已撤牌"),
    EXPIRED("已过期");

    private final String label;

    ListingStatus(String label) {
        this.label = label;
    }

    /** 仍可被摘牌 */
    public boolean isTakeable() {
        return this == LISTED || this == PARTIAL_TAKEN;
    }
}
