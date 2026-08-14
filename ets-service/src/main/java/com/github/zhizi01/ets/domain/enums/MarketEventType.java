package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/**
 * 管理员可注入的市场事件。
 * priceImpact 为 magnitude=1.0 时对基准电价的相对冲击（正数推高价格）。
 */
@Getter
public enum MarketEventType implements LabeledEnum {

    FUEL_PRICE_UP("燃料涨价", 0.18),
    FUEL_PRICE_DOWN("燃料降价", -0.15),
    LOAD_SURGE("负荷激增", 0.22),
    LOAD_DROP("负荷骤降", -0.18),
    RENEWABLE_HIGH("新能源大发", -0.20),
    RENEWABLE_LOW("新能源小发", 0.16),
    UNIT_OUTAGE("机组非停", 0.25),
    POLICY_PRICE_CAP("限价政策", -0.10),
    WEATHER_EXTREME("极端天气", 0.30);

    private final String label;
    private final double priceImpact;

    MarketEventType(String label, double priceImpact) {
        this.label = label;
        this.priceImpact = priceImpact;
    }
}
