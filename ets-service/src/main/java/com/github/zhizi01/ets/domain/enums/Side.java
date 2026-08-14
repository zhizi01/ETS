package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 买卖方向。电力市场中 BUY = 购电（用电侧），SELL = 售电（发电侧）。 */
@Getter
public enum Side implements LabeledEnum {

    BUY("买入"),
    SELL("卖出");

    private final String label;

    Side(String label) {
        this.label = label;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
