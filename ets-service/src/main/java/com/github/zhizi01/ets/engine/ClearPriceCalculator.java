package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.model.Order;

import java.util.TreeSet;

/**
 * 集中竞价出清价：在全部申报价上求最大成交量，区间取中点并对齐 tick，再夹到限价。
 * 抽成无状态工具，便于单测，也给现货出清复用同一套「最大成交量」口径。
 */
public final class ClearPriceCalculator {

    private ClearPriceCalculator() {
    }

    public static double find(Iterable<Order> buys, Iterable<Order> sells,
                              double tickSize, double priceFloor, double priceCap) {
        TreeSet<Double> candidates = new TreeSet<>();
        boolean hasBuy = false;
        boolean hasSell = false;
        for (Order o : buys) {
            if (o != null && Nums.isPositive(o.remainQty())) {
                candidates.add(o.getPrice());
                hasBuy = true;
            }
        }
        for (Order o : sells) {
            if (o != null && Nums.isPositive(o.remainQty())) {
                candidates.add(o.getPrice());
                hasSell = true;
            }
        }
        if (!hasBuy || !hasSell || candidates.isEmpty()) {
            return 0d;
        }

        double best = 0;
        double lo = 0;
        double hi = 0;
        for (Double p : candidates) {
            double demand = 0;
            for (Order b : buys) {
                if (b != null && Nums.gte(b.getPrice(), p)) {
                    demand += b.remainQty();
                }
            }
            double supply = 0;
            for (Order s : sells) {
                if (s != null && Nums.gte(p, s.getPrice())) {
                    supply += s.remainQty();
                }
            }
            double vol = Math.min(demand, supply);
            if (!Nums.isPositive(vol)) {
                continue;
            }
            if (Nums.gt(vol, best)) {
                best = vol;
                lo = p;
                hi = p;
            } else if (Nums.eq(vol, best)) {
                hi = p;
            }
        }
        if (!Nums.isPositive(best)) {
            return 0d;
        }
        double mid = Nums.alignTick((lo + hi) / 2, tickSize);
        return Nums.price(Nums.clamp(mid, priceFloor, priceCap));
    }
}
