package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 日前现货报价状态 */
@Getter
public enum SpotBidStatus implements LabeledEnum {

    ACTIVE("已申报"),
    CLEARED("已出清"),
    CANCELLED("已撤销");

    private final String label;

    SpotBidStatus(String label) {
        this.label = label;
    }
}
