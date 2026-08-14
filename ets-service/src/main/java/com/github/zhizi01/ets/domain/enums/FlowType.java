package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 资金流水类型 */
@Getter
public enum FlowType implements LabeledEnum {

    DEPOSIT("初始注资"),
    FREEZE("冻结"),
    UNFREEZE("解冻"),
    TRADE_PAY("购电付款"),
    TRADE_RECV("售电收款"),
    FEE("手续费"),
    SETTLE("结算"),
    DEVIATION("偏差考核"),
    ADJUST("管理员调整");

    private final String label;

    FlowType(String label) {
        this.label = label;
    }
}
