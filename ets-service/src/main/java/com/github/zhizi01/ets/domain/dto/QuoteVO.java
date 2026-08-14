package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Quote;
import lombok.Data;

/** 公开行情快照，比 Quote 多带枚举原值与展示名，省去前端自行拼接。 */
@Data
public class QuoteVO {

    private String simId;
    private String instrumentKey;
    /** 展示名，如「月度 全时段」 */
    private String name;
    private String variety;
    private String varietyLabel;
    private String periodType;
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

    private double bid1;
    private double bid1Qty;
    private double ask1;
    private double ask1Qty;

    private int activeSessions;
    private int activeListings;
    private long updatedAt;

    public static QuoteVO of(Quote q) {
        if (q == null) {
            return null;
        }
        QuoteVO v = new QuoteVO();
        v.simId = q.getSimId();
        v.instrumentKey = q.getInstrumentKey();
        v.prevClose = q.getPrevClose();
        v.open = q.getOpen();
        v.high = q.getHigh();
        v.low = q.getLow();
        v.last = q.getLast();
        v.avg = q.getAvg();
        v.change = q.getChange();
        v.changePct = q.getChangePct();
        v.volume = q.getVolume();
        v.amount = q.getAmount();
        v.tradeCount = q.getTradeCount();
        v.bid1 = q.getBid1();
        v.bid1Qty = q.getBid1Qty();
        v.ask1 = q.getAsk1();
        v.ask1Qty = q.getAsk1Qty();
        v.activeSessions = q.getActiveSessions();
        v.activeListings = q.getActiveListings();
        v.updatedAt = q.getUpdatedAt();
        v.varietyLabel = q.getVarietyLabel();
        v.periodLabel = q.getPeriodLabel();
        fillEnums(v);
        return v;
    }

    /** instrumentKey 形如 MONTHLY_ALL，末段是 PeriodType，其余为 Variety */
    private static void fillEnums(QuoteVO v) {
        String ik = v.instrumentKey;
        if (ik == null) {
            return;
        }
        int cut = ik.lastIndexOf('_');
        if (cut <= 0) {
            return;
        }
        String varietyName = ik.substring(0, cut);
        String periodName = ik.substring(cut + 1);
        try {
            Variety variety = Variety.valueOf(varietyName);
            v.variety = variety.name();
            v.varietyLabel = variety.getLabel();
        } catch (IllegalArgumentException ignored) {
            v.variety = varietyName;
        }
        try {
            PeriodType period = PeriodType.valueOf(periodName);
            v.periodType = period.name();
            v.periodLabel = period.getLabel();
        } catch (IllegalArgumentException ignored) {
            v.periodType = periodName;
        }
        v.name = (v.varietyLabel == null ? varietyName : v.varietyLabel)
                + " " + (v.periodLabel == null ? periodName : v.periodLabel);
    }
}
