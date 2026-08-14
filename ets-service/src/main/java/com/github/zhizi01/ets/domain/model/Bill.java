package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 结算单。按周期（如 2026-08）对账户做一次清算。 */
@Data
public class Bill {

    private String id;
    private String simId;
    private String accountId;
    private String accountCode;
    private String accountName;
    /** 结算周期，如 2026-08；终盘结算用 FINAL */
    private String period;

    /** 中长期合同电量与电费 */
    private double contractQty;
    private double contractAmount;
    /** 实际交割电量 */
    private double actualQty;
    /** 偏差电量与考核费用 */
    private double deviationQty;
    private double deviationPenalty;
    /** 现货电量与电费 */
    private double spotQty;
    private double spotAmount;

    private double feeTotal;
    private double revenue;
    private double cost;
    private double profit;

    private int rank;
    private long settledAt;
}
