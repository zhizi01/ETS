package com.github.zhizi01.ets.service.clearing;

import com.github.zhizi01.ets.api.ClearingPort;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Trade;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/** ClearingPort 的实现，对外只暴露「签合同」与「出结算单」两件事。 */
@Service
@RequiredArgsConstructor
public class ClearingServiceImpl implements ClearingPort {

    private final SettlementService settlementService;

    /** ContractService 反过来也会用到 ClearingPort，用 @Lazy 打破这层构造期环。 */
    @Lazy
    @Autowired
    private ContractService contractService;

    @Override
    public Contract createContract(Trade trade, String deliveryStart, String deliveryEnd) {
        return contractService.createContract(trade, deliveryStart, deliveryEnd);
    }

    @Override
    public List<Bill> settle(String simId, String period) {
        return settlementService.settle(simId, period);
    }
}
