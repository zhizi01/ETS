package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 分时段类型。priceFactor 为相对全时段基准电价的典型比价。 */
@Getter
public enum PeriodType implements LabeledEnum {

    ALL("全时段", 1.00),
    PEAK("峰段", 1.35),
    FLAT("平段", 1.00),
    VALLEY("谷段", 0.62),
    SHARP("尖峰", 1.65);

    private final String label;
    private final double priceFactor;

    PeriodType(String label, double priceFactor) {
        this.label = label;
        this.priceFactor = priceFactor;
    }
}
