package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.ContractStatus;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import lombok.Data;

/** 购售电合同。每笔成交生成一份。 */
@Data
public class Contract {

    private String id;
    private String simId;
    private String tradeId;
    private String sessionId;

    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;
    private TradeMode tradeMode;

    private String buyAccountId;
    private String buyAccountCode;
    private String buyAccountName;
    private String sellAccountId;
    private String sellAccountCode;
    private String sellAccountName;

    private double price;
    private double quantity;
    private double amount;

    private String deliveryStart;
    private String deliveryEnd;

    private ContractStatus status = ContractStatus.EFFECTIVE;
    private long signedAt;
    /** 实际交割电量，结算时回填 */
    private double deliveredQty;
}
