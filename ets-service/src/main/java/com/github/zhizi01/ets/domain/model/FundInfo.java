package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 账户资金。available + frozen + margin 恒等于账户当前总权益中的现金部分。 */
@Data
public class FundInfo {

    /** 可用资金 */
    private double available;
    /** 买单冻结的货款 */
    private double frozen;
    /** 卖单占用的保证金 */
    private double margin;
    /** 累计注资 */
    private double totalDeposit;

    public double total() {
        return available + frozen + margin;
    }
}
