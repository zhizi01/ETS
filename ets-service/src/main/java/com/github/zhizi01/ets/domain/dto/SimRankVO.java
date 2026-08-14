package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import lombok.Data;

/** 排行榜行，score 取自 ets:sim:{id}:rank 的 totalPnl。 */
@Data
public class SimRankVO {

    private int rank;
    private String accountId;
    private String code;
    private String name;
    private MemberType memberType;
    private String memberTypeLabel;
    private OrderSource source;
    private String sourceLabel;

    private double totalPnl;
    private double realizedPnl;
    private double unrealizedPnl;
    private double returnPct;
    private int tradeCount;
    private double buyMwh;
    private double sellMwh;
}
