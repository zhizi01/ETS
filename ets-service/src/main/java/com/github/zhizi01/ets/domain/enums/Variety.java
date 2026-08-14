package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 交易品种 */
@Getter
public enum Variety implements LabeledEnum {

    ANNUAL("年度"),
    MONTHLY("月度"),
    MULTI_DAY("月内多日"),
    SPOT_DA("日前现货");

    private final String label;

    Variety(String label) {
        this.label = label;
    }

    /** 中长期品种（区别于现货） */
    public boolean isMediumLongTerm() {
        return this != SPOT_DA;
    }
}
