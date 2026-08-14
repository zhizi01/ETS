package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.*;
import lombok.Data;

/** 挂牌单。对手方通过摘牌成交。 */
@Data
public class Listing {

    private String id;
    private String simId;
    private String sessionId;
    private String accountId;
    private String accountCode;
    private String accountName;
    private String userId;
    private OrderSource source = OrderSource.HUMAN;

    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;
    /** 挂牌方的买卖方向：SELL 表示挂牌售电，BUY 表示挂牌购电 */
    private Side side;

    private double price;
    private double totalQty;
    private double remainQty;
    /** 单次摘牌最小电量 */
    private double minTakeQty;
    /** 是否允许部分摘牌；false 时必须整量摘取 */
    private boolean allowPartial = true;

    private long validUntil;
    private ListingStatus status = ListingStatus.LISTED;

    private String title;
    private String remark;

    private long createdAt;
    private long updatedAt;

    public boolean expired(long now) {
        return validUntil > 0 && now >= validUntil;
    }

    public String instrumentKey() {
        return TradingSession.instrumentKey(variety, periodType);
    }
}
