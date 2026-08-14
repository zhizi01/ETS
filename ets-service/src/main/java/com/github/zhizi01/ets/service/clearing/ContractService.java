package com.github.zhizi01.ets.service.clearing;

import com.github.zhizi01.ets.api.ClearingPort;
import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.ContractStatus;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.ContractRepo;
import com.github.zhizi01.ets.repo.TradeRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成交落地枢纽。五种交易方式（滚动撮合 / 集中竞价 / 挂牌摘牌 / 双边协商 / 现货出清）
 * 产生的 Trade 都必须经由 {@link #commitTrade} 走同一条链路，保证
 * 「落库 → 资金结算 → 签合同 → 刷行情 → 推送」的顺序与副作用完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final ContractRepo contractRepo;
    private final TradeRepo tradeRepo;
    private final IdGen idGen;

    @Lazy
    @Autowired
    private FundPort fundPort;
    @Lazy
    @Autowired
    private MarketPort marketPort;
    @Lazy
    @Autowired
    private ClearingPort clearingPort;

    /**
     * 构造一笔成交。金额、双方手续费、成交序号在这里一次算齐，
     * 避免各引擎各算一套导致手续费口径漂移。
     */
    public Trade newTrade(Sim sim, String sessionId, Variety variety, PeriodType periodType, TradeMode mode,
                          String buyRef, String sellRef, TradingAccount buyer, TradingAccount seller,
                          double price, double quantity, Side takerSide) {
        double p = Nums.price(price);
        double q = Nums.qty(quantity);
        double amount = Nums.amount(p * q);

        Trade t = new Trade();
        t.setId(idGen.next(IdGen.TRADE));
        t.setSimId(sim.getId());
        t.setSessionId(sessionId);
        t.setVariety(variety);
        t.setPeriodType(periodType == null ? PeriodType.ALL : periodType);
        t.setTradeMode(mode);
        t.setBuyOrderId(buyRef);
        t.setSellOrderId(sellRef);
        t.setBuyAccountId(buyer.getId());
        t.setBuyAccountCode(buyer.getCode());
        t.setSellAccountId(seller.getId());
        t.setSellAccountCode(seller.getCode());
        t.setPrice(p);
        t.setQuantity(q);
        t.setAmount(amount);
        t.setBuyFee(Nums.amount(amount * sim.getCfg().getFeeRateBuy()));
        t.setSellFee(Nums.amount(amount * sim.getCfg().getFeeRateSell()));
        t.setTakerSide(takerSide);
        t.setTradedAt(System.currentTimeMillis());
        t.setSeq(idGen.nextSeq());
        return t;
    }

    /**
     * 成交后的统一处理。调用方必须已经把双方的订单级预留（买方货款 / 卖方保证金）释放回可用资金，
     * 因为 FundPort.applyTrade 是按可用资金扣款的。
     */
    public Contract commitTrade(Trade trade, String deliveryStart, String deliveryEnd) {
        tradeRepo.saveTrade(trade);
        fundPort.applyTrade(trade);
        Contract contract = clearingPort.createContract(trade, deliveryStart, deliveryEnd);
        // onTrade 之外不要再推 trade 主题：它推的是脱敏后的行情视图，
        // 这里重复推原始 Trade 会让前端收到重复帧，并把双方账户 id 暴露给公开订阅者。
        // 注意 onTrade 内部会自行读改写场次的价量统计，调用方不得用旧快照回写场次。
        marketPort.onTrade(trade);
        return contract;
    }

    /** 每笔成交签一份购售电合同。 */
    public Contract createContract(Trade trade, String deliveryStart, String deliveryEnd) {
        Contract c = new Contract();
        c.setId(idGen.next(IdGen.CONTRACT));
        c.setSimId(trade.getSimId());
        c.setTradeId(trade.getId());
        c.setSessionId(trade.getSessionId());
        c.setVariety(trade.getVariety());
        c.setPeriodType(trade.getPeriodType());
        c.setTradeMode(trade.getTradeMode());
        c.setBuyAccountId(trade.getBuyAccountId());
        c.setBuyAccountCode(trade.getBuyAccountCode());
        c.setSellAccountId(trade.getSellAccountId());
        c.setSellAccountCode(trade.getSellAccountCode());
        c.setPrice(trade.getPrice());
        c.setQuantity(trade.getQuantity());
        c.setAmount(trade.getAmount());
        c.setDeliveryStart(deliveryStart);
        c.setDeliveryEnd(deliveryEnd);
        c.setStatus(ContractStatus.EFFECTIVE);
        c.setSignedAt(System.currentTimeMillis());
        contractRepo.saveContract(c);
        return c;
    }

    public List<Contract> listByAccount(String accountId) {
        return contractRepo.listByAccount(accountId);
    }

    public Contract find(String contractId) {
        return contractRepo.find(contractId);
    }

    public void save(Contract c) {
        contractRepo.saveContract(c);
    }
}
