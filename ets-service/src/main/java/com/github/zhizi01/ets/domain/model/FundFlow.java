package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.FlowType;
import lombok.Data;

/** 资金流水。每一次账户余额变动都留痕。 */
@Data
public class FundFlow {

    private String id;
    private String simId;
    private String accountId;
    private FlowType type;

    /** 变动额，正为流入负为流出 */
    private double amount;
    /** 变动后可用余额 */
    private double balanceAfter;

    private String refId;
    private String remark;
    private long createdAt;
}
