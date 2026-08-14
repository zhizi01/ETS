package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import lombok.Data;

/**
 * 交易场次：一个(品种 + 时段 + 交易方式)的可交易窗口，拥有独立订单簿与行情统计。
 */
@Data
public class TradingSession {

    private String id;
    private String simId;
    private String name;

    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;
    private TradeMode tradeMode;
    private TSessionState state = TSessionState.PENDING;

    /** 交割区间 yyyy-MM-dd */
    private String deliveryStart;
    private String deliveryEnd;

    /** 场次时间轴（epochMillis，实时 1:1） */
    private long preOpenTime;
    private long openTime;
    private long closeTime;

    /** 申报价格上下限 元/MWh */
    private double priceFloor = 100;
    private double priceCap = 800;
    /** 单笔电量区间 MWh */
    private double minQty = 100;
    private double maxQty = 500_000;
    /** 最小变动价位 元 / 最小电量单位 MWh */
    private double tickSize = 0.01;
    private double lotSize = 1;

    // ── 行情统计 ────────────────────────────────
    private double prevClose;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private double lastPrice;
    private double avgPrice;
    private double totalVolume;
    private double totalAmount;
    /** 集中竞价 / 现货的统一出清价 */
    private double clearPrice;

    private long createdAt;
    private long updatedAt;

    /** 行情标的键，形如 MONTHLY_ALL */
    public String instrumentKey() {
        return variety.name() + "_" + (periodType == null ? PeriodType.ALL : periodType).name();
    }

    public static String instrumentKey(Variety v, PeriodType p) {
        return v.name() + "_" + (p == null ? PeriodType.ALL : p).name();
    }
}
