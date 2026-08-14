package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import lombok.Data;

/** 成交记录。四种交易方式的成交最终都归一到这里。 */
@Data
public class Trade {

    private String id;
    private String simId;
    private String sessionId;
    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;
    private TradeMode tradeMode;

    /** 订单簿撮合时为 Order.id；挂牌摘牌为 Listing.id / TakeOrder.id；双边为 Negotiation.id */
    private String buyOrderId;
    private String sellOrderId;
    private String buyAccountId;
    private String sellAccountId;
    private String buyAccountCode;
    private String sellAccountCode;

    private double price;
    private double quantity;
    private double amount;
    private double buyFee;
    private double sellFee;

    /** 主动方，用于行情染色（外盘/内盘） */
    private Side takerSide;

    private long tradedAt;
    private long seq;

    public String instrumentKey() {
        return TradingSession.instrumentKey(variety, periodType);
    }
}
