package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/**
 * 市场基本面。机器人定价与行情漂移的共同锚点，受市场事件与管理员干预影响。
 * 各 Idx / Factor 以 1.0 为基准。
 */
@Data
public class Fundamental {

    private String simId;

    /** 燃料价格指数 */
    private double coalPriceIdx = 1.0;
    private double gasPriceIdx = 1.0;
    /** 负荷水平 */
    private double loadFactor = 1.0;
    /** 新能源出力水平 */
    private double windFactor = 1.0;
    private double solarFactor = 1.0;
    /** 机组非停比例 */
    private double outageRatio = 0.03;

    /** 当前基准电价 元/MWh（由 SimConfig.benchmarkPrice 叠加事件冲击得出） */
    private double benchmarkPrice = 380;

    /** 当前生效事件的累计价格冲击 */
    private double eventImpact;

    private long updatedAt;
}
