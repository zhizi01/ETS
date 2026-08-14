package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Trade;

/**
 * MySQL 异步归档。实现方：归档模块。
 * 默认关闭时走空实现，数据库不可达不得影响 Redis 主路径。
 */
public interface ArchivePort {

    void offerTrade(Trade trade);

    void offerContract(Contract contract);

    void offerBill(Bill bill);
}
