package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 交易方式 */
@Getter
public enum TradeMode implements LabeledEnum {

    BILATERAL("双边协商"),
    CALL_AUCTION("集中竞价"),
    LISTING("挂牌摘牌"),
    ROLLING_MATCH("滚动撮合"),
    SPOT_CLEARING("现货集中出清");

    private final String label;

    TradeMode(String label) {
        this.label = label;
    }

    /** 使用集中申报 + 统一出清的方式（需要 PRE_OPEN 与 CLEARING 阶段） */
    public boolean isCentralized() {
        return this == CALL_AUCTION || this == SPOT_CLEARING;
    }

    /** 使用连续订单簿撮合 */
    public boolean usesOrderBook() {
        return this == ROLLING_MATCH || this == CALL_AUCTION;
    }
}
