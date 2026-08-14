package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.*;
import lombok.Data;

/**
 * 申报单，用于 ROLLING_MATCH（连续撮合）与 CALL_AUCTION（集中竞价）。
 */
@Data
public class Order {

    private String id;
    private String simId;
    private String sessionId;
    private String accountId;
    private String userId;
    private OrderSource source = OrderSource.HUMAN;

    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;
    private TradeMode tradeMode;
    private Side side;
    private TimeInForce timeInForce = TimeInForce.GFD;

    /** 申报价 元/MWh */
    private double price;
    /** 申报电量 MWh */
    private double quantity;
    /** 已成交电量 MWh */
    private double filledQty;
    /** 成交均价 */
    private double avgFillPrice;
    /** 买单冻结货款 / 卖单冻结保证金 */
    private double frozenAmount;

    private OrderStatus status = OrderStatus.ACTIVE;
    private String rejectReason;

    /** 全局递增撮合序号，同价位按此实现时间优先 */
    private long seq;

    private long createdAt;
    private long updatedAt;
    private long canceledAt;

    public double remainQty() {
        return quantity - filledQty;
    }

    public String instrumentKey() {
        return TradingSession.instrumentKey(variety, periodType);
    }
}
