package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import lombok.Data;

/**
 * 交易账户。一个用户在同一模拟盘下可创建多个账户（受 SimConfig.maxAccountsPerUser 限制），
 * 机器人也各自持有一个账户（source=ROBOT）。
 */
@Data
public class TradingAccount {

    private String id;
    private String simId;
    private String userId;
    /** 账户编号，形如 G0001 / C0001 / R0001 */
    private String code;
    private String name;
    private MemberType memberType;
    private OrderSource source = OrderSource.HUMAN;
    /** source=ROBOT 时指向对应机器人 */
    private String robotId;

    /** 开盘后置 true，参数不可再改 */
    private boolean paramsLocked;
    private long lockedAt;
    private boolean frozen;

    private AccountParams params = new AccountParams();
    private FundInfo fund = new FundInfo();
    private AccountStat stat = new AccountStat();

    private long createdAt;
    private long updatedAt;

    /** 已售电量占年度可发电量的比例，用于卖方可售电量校验 */
    public double soldMwh() {
        return stat == null ? 0 : stat.getSellMwh();
    }

    public double boughtMwh() {
        return stat == null ? 0 : stat.getBuyMwh();
    }
}
