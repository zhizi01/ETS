package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/**
 * 持仓（按 instrumentKey 聚合）。
 * netQty > 0 表示净买入（持有购电合约），< 0 表示净卖出（承担售电义务）。
 */
@Data
public class Position {

    private String accountId;
    private String instrumentKey;

    private double buyQty;
    private double sellQty;
    private double buyAmount;
    private double sellAmount;

    /** 净持仓 MWh */
    private double netQty;
    /** 净持仓成本均价 */
    private double avgPrice;
    /** 最新市价，盯市用 */
    private double lastPrice;
    private double unrealizedPnl;
    private double realizedPnl;

    private long updatedAt;

    public double avgBuyPrice() {
        return buyQty <= 0 ? 0 : buyAmount / buyQty;
    }

    public double avgSellPrice() {
        return sellQty <= 0 ? 0 : sellAmount / sellQty;
    }
}
