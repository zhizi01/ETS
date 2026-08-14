package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.model.Trade;

/** 资金与盈亏。实现方：账户模块。 */
public interface FundPort {

    void deposit(String accountId, double amount, String remark);

    /** 管理员手工调整资金，可正可负 */
    void adjust(String accountId, double amount, String remark);

    /** 买单冻结货款（含手续费）。资金不足返回 false，不抛异常。 */
    boolean freezeForBuy(String accountId, double amount, String refId);

    /** 卖单冻结保证金。不足返回 false。 */
    boolean freezeMargin(String accountId, double amount, String refId);

    /** 撤单/未成交部分解冻货款 */
    void unfreezeBuy(String accountId, double amount, String refId);

    /** 撤单/未成交部分释放保证金 */
    void releaseMargin(String accountId, double amount, String refId);

    /**
     * 成交后的一次性结算：扣款/收款、手续费、持仓更新、已实现盈亏、账户统计、排行榜。
     * 双方账户都在此方法内处理完毕。
     */
    void applyTrade(Trade trade);

    /** 按最新价对该账户的全部持仓做盯市，刷新浮动盈亏与排行榜 */
    void markToMarket(String accountId);
}
