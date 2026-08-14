package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 账户交易统计与盈亏。 */
@Data
public class AccountStat {

    private double realizedPnl;
    private double unrealizedPnl;
    private double totalPnl;

    private int tradeCount;
    private int orderCount;

    private double buyMwh;
    private double sellMwh;
    private double buyAmount;
    private double sellAmount;
    private double avgBuyPrice;
    private double avgSellPrice;
    private double feePaid;

    /** 当日盈亏，跨自然日时由结算任务归零 */
    private double todayPnl;
    private int rank;
}
