package com.github.zhizi01.ets.domain.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 组合分析，对应 GET /api/user/portfolio?accountId=。 */
@Data
public class AcctPortfolioVO {

    private String accountId;
    private String code;
    private String name;

    /** 按标的聚合的品种分布 */
    private List<VarietySlice> varietyDist = new ArrayList<>();
    /** 买卖均价对比（按标的） */
    private List<PriceCompare> priceCompare = new ArrayList<>();
    /** 累计已实现盈亏曲线，按成交时间升序 */
    private List<PnlPoint> pnlCurve = new ArrayList<>();

    private double totalBuyMwh;
    private double totalSellMwh;
    private double totalBuyAmount;
    private double totalSellAmount;
    private double realizedPnl;
    private double unrealizedPnl;
    private double totalPnl;

    @Data
    public static class VarietySlice {
        private String instrumentKey;
        private String label;
        private double buyQty;
        private double sellQty;
        private double netQty;
        /** 该标的的成交额合计，用于饼图权重 */
        private double amount;
        private double unrealizedPnl;
    }

    @Data
    public static class PriceCompare {
        private String instrumentKey;
        private String label;
        private double avgBuyPrice;
        private double avgSellPrice;
        private double lastPrice;
    }

    @Data
    public static class PnlPoint {
        private long ts;
        private String tradeId;
        private double price;
        private double quantity;
        /** 本笔盈亏 */
        private double pnl;
        /** 累计已实现盈亏 */
        private double cumulativePnl;
    }
}
