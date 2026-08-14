package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 机器人交易策略 */
@Getter
public enum RobotStrategy implements LabeledEnum {

    MARKET_MAKER("做市商", "围绕中间价双边挂单提供流动性，库存偏离目标时单边收窄价差"),
    TREND("趋势跟随", "读取近期 K 线动量顺势追价，动量越强越激进"),
    COST_BASED("成本定价", "发电方按变动成本加成报卖价，用电方按可承受价折让报买价"),
    NOISE("噪声交易", "在中间价附近随机游走报价，制造真实的流动性与波动"),
    ARBITRAGE("跨品种套利", "监测年度/月度/现货价差，超阈值时反向建仓"),
    DEMAND("刚性需求", "临近闭市时逐步提高出价，确保完成采购或销售计划");

    private final String label;
    private final String description;

    RobotStrategy(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
