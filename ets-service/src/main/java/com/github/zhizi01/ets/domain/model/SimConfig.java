package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/**
 * 模拟盘规则配置。开盘后不建议修改（管理端会给出二次确认）。
 */
@Data
public class SimConfig {

    /** 每个用户最多可创建的交易账户数 */
    private int maxAccountsPerUser = 3;

    /** 账户初始资金允许区间（元） */
    private double initialCapitalMin = 1_000_000;
    private double initialCapitalMax = 500_000_000;

    /** 买方 / 卖方手续费率 */
    private double feeRateBuy = 0.0005;
    private double feeRateSell = 0.0005;

    /** 相对前收盘价的涨跌幅限制 */
    private double priceLimitPct = 0.15;

    /** 卖方保证金比例 */
    private double marginRatio = 0.20;

    /** 是否允许无电量支撑的裸卖空 */
    private boolean allowNakedShort = false;

    /** 偏差考核容差 */
    private double deviationTolerancePct = 0.03;

    /** 基准电价（元/MWh），行情初始化与机器人定价的锚点 */
    private double benchmarkPrice = 380;
}
