package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.TradingSession;
import lombok.Data;

/** 交易场次视图，附中文标签与倒计时，前端直接渲染。 */
@Data
public class SessVO {

    private String id;
    private String simId;
    private String name;

    private Variety variety;
    private String varietyLabel;
    private PeriodType periodType;
    private String periodLabel;
    private TradeMode tradeMode;
    private String tradeModeLabel;
    private TSessionState state;
    private String stateLabel;
    private String instrumentKey;

    private String deliveryStart;
    private String deliveryEnd;

    private long preOpenTime;
    private long openTime;
    private long closeTime;
    private long serverTime;
    /** 距离下一个状态切换的毫秒数，已过则为 0 */
    private long countdownMs;

    private double priceFloor;
    private double priceCap;
    private double minQty;
    private double maxQty;
    private double tickSize;
    private double lotSize;

    private double prevClose;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private double lastPrice;
    private double avgPrice;
    private double totalVolume;
    private double totalAmount;
    private double clearPrice;

    private long createdAt;
    private long updatedAt;

    public static SessVO of(TradingSession s) {
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        SessVO v = new SessVO();
        v.id = s.getId();
        v.simId = s.getSimId();
        v.name = s.getName();
        v.variety = s.getVariety();
        v.varietyLabel = s.getVariety() == null ? null : s.getVariety().getLabel();
        v.periodType = s.getPeriodType();
        v.periodLabel = s.getPeriodType() == null ? null : s.getPeriodType().getLabel();
        v.tradeMode = s.getTradeMode();
        v.tradeModeLabel = s.getTradeMode() == null ? null : s.getTradeMode().getLabel();
        v.state = s.getState();
        v.stateLabel = s.getState() == null ? null : s.getState().getLabel();
        v.instrumentKey = s.getVariety() == null ? null : s.instrumentKey();
        v.deliveryStart = s.getDeliveryStart();
        v.deliveryEnd = s.getDeliveryEnd();
        v.preOpenTime = s.getPreOpenTime();
        v.openTime = s.getOpenTime();
        v.closeTime = s.getCloseTime();
        v.serverTime = now;
        v.countdownMs = countdown(s, now);
        v.priceFloor = s.getPriceFloor();
        v.priceCap = s.getPriceCap();
        v.minQty = s.getMinQty();
        v.maxQty = s.getMaxQty();
        v.tickSize = s.getTickSize();
        v.lotSize = s.getLotSize();
        v.prevClose = s.getPrevClose();
        v.openPrice = s.getOpenPrice();
        v.highPrice = s.getHighPrice();
        v.lowPrice = s.getLowPrice();
        v.lastPrice = s.getLastPrice();
        v.avgPrice = s.getAvgPrice();
        v.totalVolume = s.getTotalVolume();
        v.totalAmount = s.getTotalAmount();
        v.clearPrice = s.getClearPrice();
        v.createdAt = s.getCreatedAt();
        v.updatedAt = s.getUpdatedAt();
        return v;
    }

    private static long countdown(TradingSession s, long now) {
        long target = switch (s.getState() == null ? TSessionState.PENDING : s.getState()) {
            case PENDING -> s.getPreOpenTime() > 0 ? s.getPreOpenTime() : s.getOpenTime();
            case PRE_OPEN, OPEN, SUSPENDED -> s.getCloseTime();
            default -> 0;
        };
        return target > now ? target - now : 0;
    }
}
