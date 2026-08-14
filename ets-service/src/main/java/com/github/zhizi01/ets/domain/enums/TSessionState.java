package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 交易场次状态 */
@Getter
public enum TSessionState implements LabeledEnum {

    PENDING("未开始"),
    PRE_OPEN("集中申报"),
    OPEN("交易中"),
    SUSPENDED("临时停牌"),
    CLEARING("出清中"),
    CLOSED("已闭市"),
    SETTLED("已结算");

    private final String label;

    TSessionState(String label) {
        this.label = label;
    }

    /** 允许接收新申报 */
    public boolean acceptsOrders() {
        return this == OPEN || this == PRE_OPEN;
    }

    /** 允许即时撮合成交（集中申报阶段只收单不撮合） */
    public boolean allowsMatching() {
        return this == OPEN;
    }

    /** 允许撤单 */
    public boolean allowsCancel() {
        return this == OPEN || this == PRE_OPEN || this == SUSPENDED;
    }
}
