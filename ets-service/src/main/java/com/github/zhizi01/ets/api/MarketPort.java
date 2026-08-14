package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.model.Quote;
import com.github.zhizi01.ets.domain.model.Trade;

/** 行情。实现方：行情模块。 */
public interface MarketPort {

    /** 成交回调：刷新场次统计、标的快照、K 线，并推送行情 */
    void onTrade(Trade trade);

    /** 订单簿变化回调：节流后推送盘口 */
    void onBookChanged(String sessionId);

    Quote quote(String simId, String instrumentKey);

    /**
     * 参考价，供机器人定价与涨跌幅校验：
     * 依次取 最新成交价 -> 场次前收 -> 基本面基准价 × 时段比价。
     */
    double referencePrice(String simId, String instrumentKey);

    /** 某场次的中间价，无盘口时退化为参考价 */
    double midPrice(String sessionId);

    Depth depth(String sessionId, int level);

    /** 初始化一个标的的行情（建场次时调用） */
    void initInstrument(String simId, String instrumentKey, double basePrice);
}
