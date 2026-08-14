package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.domain.model.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClearPriceCalculatorTest {

    @Test
    void noCrossReturnsZero() {
        double p = ClearPriceCalculator.find(
                List.of(order(360, 100)),
                List.of(order(400, 100)),
                0.01, 0, 1000);
        assertEquals(0d, p);
    }

    @Test
    void singleCrossingPrice() {
        double p = ClearPriceCalculator.find(
                List.of(order(400, 100)),
                List.of(order(380, 80)),
                0.01, 0, 1000);
        assertEquals(390, p);
    }

    @Test
    void intervalTakesMidpointThenTick() {
        // 买 410/390，卖 370/390：最大成交量落在 [390, 410] 与卖盘交叉的区间
        double p = ClearPriceCalculator.find(
                List.of(order(410, 50), order(390, 50)),
                List.of(order(370, 40), order(390, 40)),
                1, 0, 1000);
        assertEquals(390, p);
    }

    @Test
    void clampsToPriceBand() {
        // 最大成交量区间中点为 300，再夹到 [350, 400]
        double p = ClearPriceCalculator.find(
                List.of(order(500, 100)),
                List.of(order(100, 100)),
                1, 350, 400);
        assertEquals(350, p);
    }

    @Test
    void emptyBookReturnsZero() {
        assertEquals(0d, ClearPriceCalculator.find(List.of(), List.of(), 0.01, 0, 1000));
    }

    private static Order order(double price, double qty) {
        Order o = new Order();
        o.setPrice(price);
        o.setQuantity(qty);
        o.setFilledQty(0);
        return o;
    }
}
