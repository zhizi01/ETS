package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 标的行情快照，按 (simId, instrumentKey) 聚合该标的下所有场次的成交。 */
@Data
public class Quote {

    private String simId;
    private String instrumentKey;
    private String varietyLabel;
    private String periodLabel;

    private double prevClose;
    private double open;
    private double high;
    private double low;
    private double last;
    private double avg;
    private double change;
    private double changePct;

    private double volume;
    private double amount;
    private int tradeCount;

    /** 买一 / 卖一，来自该标的下活跃的滚动撮合场次 */
    private double bid1;
    private double bid1Qty;
    private double ask1;
    private double ask1Qty;

    /** 参与该标的的活跃场次数与挂牌数 */
    private int activeSessions;
    private int activeListings;

    private long updatedAt;
}
