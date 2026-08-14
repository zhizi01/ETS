package com.github.zhizi01.ets.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 日前现货报价曲线上的一个小时段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpotSegment {

    /** 0-23 */
    private int hour;
    /** 申报价 元/MWh */
    private double price;
    /** 申报电量 MWh */
    private double quantity;

    /** 出清后回填 */
    private double clearedQty;
    private double clearedPrice;

    public SpotSegment(int hour, double price, double quantity) {
        this.hour = hour;
        this.price = price;
        this.quantity = quantity;
    }
}
