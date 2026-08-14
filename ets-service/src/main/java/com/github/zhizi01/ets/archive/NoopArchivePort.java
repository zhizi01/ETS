package com.github.zhizi01.ets.archive;

import com.github.zhizi01.ets.api.ArchivePort;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Trade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MySQL 未启用时的空实现，保证业务模块始终能注入 ArchivePort。 */
@Component
@ConditionalOnProperty(prefix = "ets.mysql", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopArchivePort implements ArchivePort {

    @Override
    public void offerTrade(Trade trade) {
        // Redis 是权威存储，归档关闭时什么都不做
    }

    @Override
    public void offerContract(Contract contract) {
        // 同上
    }

    @Override
    public void offerBill(Bill bill) {
        // 同上
    }
}
