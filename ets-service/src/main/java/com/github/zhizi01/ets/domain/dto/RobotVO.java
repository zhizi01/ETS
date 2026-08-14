package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import lombok.Data;

/** 管理端机器人列表项：机器人本体 + 其交易账户的资金与战绩。 */
@Data
public class RobotVO {

    private String id;
    private String simId;
    private String accountId;
    private String accountCode;
    private String name;
    private String memberType;
    private String memberTypeLabel;
    private String strategy;
    private String strategyLabel;
    private boolean enabled;
    /** 引擎里是否真的有 tick 任务在跑 */
    private boolean running;

    private RobotConfig cfg;

    private int orderCount;
    private int tradeCount;
    private int cancelCount;
    private long lastActAt;
    private String lastAction;

    // ── 来自交易账户 ────────────────────────────────────
    private String genType;
    private String loadProfile;
    private double initialCapital;
    private double available;
    private double frozen;
    private double margin;
    private double buyMwh;
    private double sellMwh;
    private double realizedPnl;
    private double totalPnl;

    private long createdAt;

    public static RobotVO of(Robot r, TradingAccount acct, boolean running) {
        if (r == null) {
            return null;
        }
        RobotVO v = new RobotVO();
        v.id = r.getId();
        v.simId = r.getSimId();
        v.accountId = r.getAccountId();
        v.accountCode = r.getAccountCode();
        v.name = r.getName();
        if (r.getMemberType() != null) {
            v.memberType = r.getMemberType().name();
            v.memberTypeLabel = r.getMemberType().getLabel();
        }
        if (r.getStrategy() != null) {
            v.strategy = r.getStrategy().name();
            v.strategyLabel = r.getStrategy().getLabel();
        }
        v.enabled = r.isEnabled();
        v.running = running;
        v.cfg = r.getCfg();
        v.orderCount = r.getOrderCount();
        v.tradeCount = r.getTradeCount();
        v.cancelCount = r.getCancelCount();
        v.lastActAt = r.getLastActAt();
        v.lastAction = r.getLastAction();
        v.createdAt = r.getCreatedAt();

        if (acct != null) {
            if (v.accountCode == null) {
                v.accountCode = acct.getCode();
            }
            if (acct.getParams() != null) {
                v.initialCapital = acct.getParams().getInitialCapital();
                v.genType = acct.getParams().getGenType() == null ? null : acct.getParams().getGenType().name();
                v.loadProfile = acct.getParams().getLoadProfile() == null ? null : acct.getParams().getLoadProfile().name();
            }
            if (acct.getFund() != null) {
                v.available = acct.getFund().getAvailable();
                v.frozen = acct.getFund().getFrozen();
                v.margin = acct.getFund().getMargin();
            }
            if (acct.getStat() != null) {
                v.buyMwh = acct.getStat().getBuyMwh();
                v.sellMwh = acct.getStat().getSellMwh();
                v.realizedPnl = acct.getStat().getRealizedPnl();
                v.totalPnl = acct.getStat().getTotalPnl();
            }
        }
        return v;
    }
}
