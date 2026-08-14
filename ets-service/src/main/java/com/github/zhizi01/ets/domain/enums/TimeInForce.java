package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 订单有效期类型 */
@Getter
public enum TimeInForce implements LabeledEnum {

    GFD("当日有效"),
    GTC("撤销前有效"),
    IOC("立即成交剩余撤销"),
    FOK("全部成交否则撤销");

    private final String label;

    TimeInForce(String label) {
        this.label = label;
    }

    /** 不允许在订单簿上留存的类型 */
    public boolean isImmediate() {
        return this == IOC || this == FOK;
    }
}
