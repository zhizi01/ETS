package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.OrderSource;
import lombok.Data;

/** 摘牌记录。摘牌即成交，一条摘牌对应一条 Trade。 */
@Data
public class TakeOrder {

    private String id;
    private String listingId;
    private String simId;
    private String sessionId;
    private String accountId;
    private String accountCode;
    private String userId;
    private OrderSource source = OrderSource.HUMAN;

    private double quantity;
    private double price;
    private double amount;
    private double fee;

    private String tradeId;
    private String contractId;
    private long createdAt;
}
