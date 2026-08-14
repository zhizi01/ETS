package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 机组类型。typicalCost 为该类型的典型变动成本（元/MWh），用于机器人默认参数与合理性校验。 */
@Getter
public enum GenType implements LabeledEnum {

    COAL("燃煤", 320),
    GAS("燃气", 430),
    HYDRO("水电", 120),
    WIND("风电", 60),
    SOLAR("光伏", 50),
    NUCLEAR("核电", 180),
    STORAGE("储能", 260);

    private final String label;
    private final double typicalCost;

    GenType(String label, double typicalCost) {
        this.label = label;
        this.typicalCost = typicalCost;
    }

    /** 出力受天气影响的新能源机组 */
    public boolean isRenewable() {
        return this == WIND || this == SOLAR;
    }
}
