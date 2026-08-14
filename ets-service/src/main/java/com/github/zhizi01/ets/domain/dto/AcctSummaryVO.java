package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MemberType;
import lombok.Data;

/** 账户总览，对应 GET /api/user/accounts/{id}/summary。 */
@Data
public class AcctSummaryVO {

    private String accountId;
    private String code;
    private String name;
    private MemberType memberType;
    private String memberTypeLabel;

    // ── 资金 ────────────────────────────────
    private double available;
    private double frozen;
    private double margin;
    private double totalDeposit;
    private double fundTotal;
    /** 现金合计 + 浮动盈亏 */
    private double equity;

    // ── 持仓 ────────────────────────────────
    private int positionCount;
    /** 净持仓电量合计（多空相抵后的绝对值之和） */
    private double positionMwh;
    /** 净持仓按最新价折算的市值 */
    private double positionValue;

    // ── 盈亏 ────────────────────────────────
    private double realizedPnl;
    private double unrealizedPnl;
    private double totalPnl;
    private double todayPnl;
    private double returnPct;

    // ── 交易统计 ────────────────────────────
    private int tradeCount;
    private double buyMwh;
    private double sellMwh;
    private double avgBuyPrice;
    private double avgSellPrice;
    private double feePaid;

    // ── 排名 ────────────────────────────────
    private long rank;
    private long totalAccounts;
}
