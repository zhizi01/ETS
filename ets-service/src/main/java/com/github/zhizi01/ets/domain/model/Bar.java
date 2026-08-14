package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** K 线。ts 为该周期的起始时间戳。 */
@Data
public class Bar {

    private long ts;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private double amount;
    private int tradeCount;

    public static Bar init(long ts, double price) {
        Bar b = new Bar();
        b.ts = ts;
        b.open = price;
        b.high = price;
        b.low = price;
        b.close = price;
        return b;
    }

    public void apply(double price, double qty) {
        if (open <= 0) {
            open = price;
            high = price;
            low = price;
        }
        high = Math.max(high, price);
        low = low <= 0 ? price : Math.min(low, price);
        close = price;
        volume += qty;
        amount += price * qty;
        tradeCount++;
    }
}
