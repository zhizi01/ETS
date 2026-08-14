package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Trade;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次撮合的结果。rejected 表示整单被拒（目前只有 FOK 无法全额成交这一种情形）。 */
@Data
public class MatchResult {

    private String sessionId;
    private Order taker;
    private List<Trade> trades = new ArrayList<>();

    private double filledQty;
    private double filledAmount;

    private boolean rejected;
    private String rejectReason;

    public void add(Trade t) {
        trades.add(t);
        filledQty = Nums.qty(filledQty + t.getQuantity());
        filledAmount = Nums.amount(filledAmount + t.getAmount());
    }

    public double avgPrice() {
        return Nums.price(Nums.div(filledAmount, filledQty));
    }

    public boolean hasTrade() {
        return !trades.isEmpty();
    }

    /**
     * 把一笔成交回填到订单上。滚动撮合与集中竞价共用，
     * 保证 filledQty / avgFillPrice / status 三者的推进方式只有一处定义。
     */
    public static void applyFill(Order o, double qty, double price) {
        double filled = Nums.qty(o.getFilledQty() + qty);
        double amount = o.getAvgFillPrice() * o.getFilledQty() + price * qty;
        o.setFilledQty(filled);
        o.setAvgFillPrice(Nums.price(Nums.div(amount, filled)));
        o.setStatus(Nums.isPositive(o.remainQty()) ? OrderStatus.PARTIAL : OrderStatus.FILLED);
        o.setUpdatedAt(System.currentTimeMillis());
    }

    public static MatchResult reject(Order taker, String reason) {
        MatchResult r = new MatchResult();
        r.setTaker(taker);
        r.setSessionId(taker == null ? null : taker.getSessionId());
        r.setRejected(true);
        r.setRejectReason(reason);
        return r;
    }
}
