package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.model.TradingAccount;

import java.util.List;

/** 交易账户查询与机器人账户创建。实现方：账户模块。 */
public interface AccountPort {

    TradingAccount find(String accountId);

    /** 找不到抛 ACCOUNT_NOT_FOUND */
    TradingAccount require(String accountId);

    /** 校验账户归属，不属于该用户抛 ACCOUNT_NOT_OWNED */
    TradingAccount requireOwned(String accountId, String userId);

    /** 校验账户可交易：模拟盘 RUNNING、账户已锁定参数、未被冻结 */
    TradingAccount requireTradable(String accountId);

    List<TradingAccount> listBySim(String simId);

    List<TradingAccount> listBySim(String simId, MemberType memberType);

    void save(TradingAccount account);

    /**
     * 为机器人创建一个交易账户（source=ROBOT，直接置 paramsLocked 并完成注资）。
     * 参数按主体类型与策略自动生成合理值。
     */
    TradingAccount createRobotAccount(String simId, MemberType memberType, RobotStrategy strategy,
                                      String name, String robotId, double initialCapital);
}
