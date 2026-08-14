package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 申报单状态 */
@Getter
public enum OrderStatus implements LabeledEnum {

    ACTIVE("挂单中"),
    PARTIAL("部分成交"),
    FILLED("全部成交"),
    CANCELLED("已撤单"),
    REJECTED("已拒绝"),
    EXPIRED("已过期");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    /** 仍在订单簿上、可继续撮合 */
    public boolean isOpen() {
        return this == ACTIVE || this == PARTIAL;
    }

    /** 终态 */
    public boolean isFinal() {
        return !isOpen();
    }
}
