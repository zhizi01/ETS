package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.service.clearing.ContractService;
import com.github.zhizi01.ets.service.trade.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 双边协商达成后的成交执行。不进订单簿、不产生盘口，直接按桌面上的最后一档价量签约。
 * 成交计入成交量统计，但不应改写公开行情的最新价——这一点由行情模块按 tradeMode=BILATERAL 处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BilateralEngine {

    private final RiskService riskService;
    private final ContractService contractService;

    @Lazy
    @Autowired
    private SimPort simPort;
    @Lazy
    @Autowired
    private AccountPort accountPort;

    /** 执行一笔已达成的协商，回填 tradeId / contractId。acceptorAccountId 决定成交的主动方。 */
    public Trade execute(Negotiation nego, String acceptorAccountId) {
        Sim sim = simPort.requireSim(nego.getSimId());
        TradingSession sess = nego.getSessionId() == null ? null : simPort.findSession(nego.getSessionId());
        TradingAccount buyer = accountPort.require(nego.buyAccountId());
        TradingAccount seller = accountPort.require(nego.sellAccountId());

        riskService.checkSimRunning(sim);
        riskService.checkAccountTradable(buyer, sess);
        riskService.checkAccountTradable(seller, sess);

        double price = Nums.price(nego.getPrice());
        double qty = Nums.qty(nego.getQuantity());
        String instrumentKey = TradingSession.instrumentKey(nego.getVariety(), nego.getPeriodType());
        riskService.checkPositionLimit(buyer, instrumentKey, Side.BUY, qty);
        riskService.checkPositionLimit(seller, instrumentKey, Side.SELL, qty);
        riskService.checkSellableEnergy(seller, qty, sim);

        // 双边没有挂单预留，用「冻结再立即释放」把资金/保证金是否充足校验掉
        String ref = "NEGO:" + nego.getId();
        double buyReserve = riskService.freezeBuy(buyer, price, qty, sim, ref);
        double sellReserve;
        try {
            sellReserve = riskService.freezeSell(seller, price, qty, sim, ref);
        } catch (RuntimeException e) {
            riskService.release(buyer.getId(), Side.BUY, buyReserve, ref);
            throw e;
        }
        riskService.release(buyer.getId(), Side.BUY, buyReserve, ref);
        riskService.release(seller.getId(), Side.SELL, sellReserve, ref);

        Side takerSide = nego.sideOf(acceptorAccountId);
        Trade t = contractService.newTrade(sim, nego.getSessionId(), nego.getVariety(), nego.getPeriodType(),
                TradeMode.BILATERAL, nego.getId(), nego.getId(), buyer, seller,
                price, qty, takerSide == null ? Side.BUY : takerSide);

        String ds = nego.getDeliveryStart() != null ? nego.getDeliveryStart()
                : (sess == null ? null : sess.getDeliveryStart());
        String de = nego.getDeliveryEnd() != null ? nego.getDeliveryEnd()
                : (sess == null ? null : sess.getDeliveryEnd());
        Contract contract = contractService.commitTrade(t, ds, de);

        nego.setTradeId(t.getId());
        nego.setContractId(contract == null ? null : contract.getId());
        log.info("双边协商成交 nego={} price={} qty={} buyer={} seller={}",
                nego.getId(), price, qty, buyer.getId(), seller.getId());
        return t;
    }
}
