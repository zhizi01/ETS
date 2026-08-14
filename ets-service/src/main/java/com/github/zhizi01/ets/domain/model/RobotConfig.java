package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 机器人行为参数。 */
@Data
public class RobotConfig {

    /** 决策间隔毫秒，实际执行会加 ±30% 抖动 */
    private long tickIntervalMs = 3000;
    /** 激进度 0-1：越高越愿意吃价、下单越频繁 */
    private double aggressiveness = 0.5;
    /** 报价相对参考价的系统性偏移 */
    private double priceBiasPct = 0;
    /** 单笔最大申报电量 MWh */
    private double maxOrderMwh = 5000;
    /** 做市价差（相对中间价） */
    private double spreadPct = 0.012;
    /** 目标库存 MWh，做市商据此调整双边报价 */
    private double inventoryTargetMwh = 0;
    /** 每次 tick 实际下单的概率 */
    private double actProbability = 0.6;
    /** 撤单概率 */
    private double cancelProbability = 0.25;
    /** 参与挂牌摘牌的概率 */
    private double listingProbability = 0.2;
    private long randomSeed;
}
