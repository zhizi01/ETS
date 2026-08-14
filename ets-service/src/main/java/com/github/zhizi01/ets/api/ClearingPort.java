package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Trade;

import java.util.List;

/** 合同与结算。实现方：撮合交易模块。 */
public interface ClearingPort {

    /** 成交后签订合同 */
    Contract createContract(Trade trade, String deliveryStart, String deliveryEnd);

    /** 对整个模拟盘按周期出结算单，period 形如 2026-08 或 FINAL */
    List<Bill> settle(String simId, String period);
}
