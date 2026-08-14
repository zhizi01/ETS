package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.AccountStat;
import com.github.zhizi01.ets.domain.model.FundInfo;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import lombok.Data;

/** 交易账户视图，附带枚举中文标签与资金合计，省去前端二次计算。 */
@Data
public class AccountVO {

    private String id;
    private String simId;
    private String simName;
    private String userId;
    private String userName;
    private String code;
    private String name;
    private MemberType memberType;
    private String memberTypeLabel;
    private OrderSource source;
    private String sourceLabel;
    private String robotId;

    private boolean paramsLocked;
    private long lockedAt;
    private boolean frozen;

    private AccountParams params;
    private FundInfo fund;
    private AccountStat stat;

    /** available + frozen + margin */
    private double fundTotal;
    /** 现金合计 + 浮动盈亏，用于排行与收益率 */
    private double equity;
    private double returnPct;

    private long createdAt;
    private long updatedAt;

    public static AccountVO of(TradingAccount a) {
        if (a == null) {
            return null;
        }
        AccountVO v = new AccountVO();
        v.id = a.getId();
        v.simId = a.getSimId();
        v.userId = a.getUserId();
        v.code = a.getCode();
        v.name = a.getName();
        v.memberType = a.getMemberType();
        v.memberTypeLabel = a.getMemberType() == null ? null : a.getMemberType().getLabel();
        v.source = a.getSource();
        v.sourceLabel = a.getSource() == null ? null : a.getSource().getLabel();
        v.robotId = a.getRobotId();
        v.paramsLocked = a.isParamsLocked();
        v.lockedAt = a.getLockedAt();
        v.frozen = a.isFrozen();
        v.params = a.getParams();
        v.fund = a.getFund();
        v.stat = a.getStat();
        v.createdAt = a.getCreatedAt();
        v.updatedAt = a.getUpdatedAt();

        FundInfo f = a.getFund();
        AccountStat s = a.getStat();
        v.fundTotal = f == null ? 0 : f.total();
        v.equity = v.fundTotal + (s == null ? 0 : s.getUnrealizedPnl());
        double deposit = f == null ? 0 : f.getTotalDeposit();
        v.returnPct = deposit <= 0 ? 0 : (s == null ? 0 : s.getTotalPnl()) / deposit;
        return v;
    }
}
