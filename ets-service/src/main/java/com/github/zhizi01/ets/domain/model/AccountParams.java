package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import lombok.Data;

/**
 * 交易账户基本参数。
 * 模拟盘 SETUP 阶段可自由编辑，一旦开盘（RUNNING）即被冻结，此后任何修改都将被拒绝。
 */
@Data
public class AccountParams {

    /** 初始资金（元） */
    private double initialCapital;

    // ── 发电企业 GENCO ──────────────────────────────
    private GenType genType;
    /** 装机容量 MW */
    private Double installedCapacityMw;
    /** 年度可发电量 MWh，同时作为可售电量上限 */
    private Double annualEnergyMwh;
    /** 变动成本 元/MWh，低于此价售电即亏损 */
    private Double variableCostPerMwh;
    /** 最小/最大出力系数 */
    private Double minOutputRatio;
    private Double maxOutputRatio;
    /** 非计划停运率，影响现货可用容量 */
    private Double forcedOutageRate;

    // ── 电力用户 CONSUMER ───────────────────────────
    /** 年度用电需求 MWh */
    private Double annualDemandMwh;
    /** 可承受最高购电价 元/MWh */
    private Double maxAcceptPricePerMwh;
    private LoadProfile loadProfile;
    /** 目录电价基准，用于测算节约收益 */
    private Double retailBenchmarkPrice;

    // ── 售电公司 RETAILER ───────────────────────────
    /** 代理用户总电量 MWh */
    private Double agentDemandMwh;
    private Integer agentUserCount;
    /** 零售套餐电价 元/MWh */
    private Double retailPricePerMwh;

    // ── 通用风控 ────────────────────────────────────
    /** 单笔最大申报电量 MWh */
    private Double maxSingleOrderMwh;
    /** 最大持仓电量 MWh */
    private Double maxPositionMwh;
    /** 风险偏好 1(保守) - 5(激进) */
    private Integer riskAppetite = 3;
}
