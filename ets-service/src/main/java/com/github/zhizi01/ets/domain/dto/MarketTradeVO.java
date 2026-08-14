package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.Trade;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 逐笔成交。只暴露行情所需字段，不带交易双方的账户身份。 */
@Data
public class MarketTradeVO {

    private String id;
    private String sessionId;
    private String instrumentKey;
    private double price;
    private double quantity;
    private double amount;
    /** BUY 表示主动买入（外盘），SELL 表示主动卖出（内盘） */
    private String takerSide;
    private String tradeMode;
    private String tradeModeLabel;
    private long tradedAt;

    public static MarketTradeVO of(Trade t) {
        if (t == null) {
            return null;
        }
        MarketTradeVO v = new MarketTradeVO();
        v.id = t.getId();
        v.sessionId = t.getSessionId();
        v.instrumentKey = t.getVariety() == null ? null : t.instrumentKey();
        v.price = t.getPrice();
        v.quantity = t.getQuantity();
        v.amount = t.getAmount();
        v.takerSide = t.getTakerSide() == null ? null : t.getTakerSide().name();
        if (t.getTradeMode() != null) {
            v.tradeMode = t.getTradeMode().name();
            v.tradeModeLabel = t.getTradeMode().getLabel();
        }
        v.tradedAt = t.getTradedAt();
        return v;
    }

    public static List<MarketTradeVO> of(List<Trade> trades) {
        List<MarketTradeVO> out = new ArrayList<>(trades == null ? 0 : trades.size());
        if (trades != null) {
            for (Trade t : trades) {
                MarketTradeVO v = of(t);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
