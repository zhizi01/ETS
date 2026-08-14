package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.AuditPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.OrderRepo;
import com.github.zhizi01.ets.service.clearing.ContractService;
import com.github.zhizi01.ets.service.trade.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 集中竞价统一出清。
 * <p>
 * 出清价的选取：以全部申报价为候选，逐一计算 min(累计需求, 累计供给)，取成交量最大的价格；
 * 若最大成交量落在一段价格区间上，取区间中点并对齐 tickSize，再夹到 [priceFloor, priceCap]。
 * 所有成交一律按该统一价成交，边际价位上的申报按比例分配，零头按 lotSize 取整后归序号最小者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallAuctionEngine {

    private final OrderBookRegistry registry;
    private final OrderRepo orderRepo;
    private final RiskService riskService;
    private final ContractService contractService;

    @Lazy
    @Autowired
    private SimPort simPort;
    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private MarketPort marketPort;
    @Lazy
    @Autowired
    private WsPort wsPort;
    @Lazy
    @Autowired
    private AuditPort auditPort;

    public void runCallAuction(String sessionId) {
        TradingSession sess = simPort.requireSession(sessionId);
        Sim sim = simPort.requireSim(sess.getSimId());
        if (sess.getState() == TSessionState.CLOSED || sess.getState() == TSessionState.SETTLED) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次已" + sess.getState().getLabel() + "，不能再次出清");
        }
        OrderBook book = registry.book(sessionId);
        List<Order> buys;
        List<Order> sells;
        synchronized (book) {
            buys = openOrders(book, Side.BUY);
            sells = openOrders(book, Side.SELL);
        }

        double clearPrice = ClearPriceCalculator.find(
                buys, sells, sess.getTickSize(), sess.getPriceFloor(), sess.getPriceCap());
        List<Order> eligibleBuys = eligible(buys, Side.BUY, clearPrice);
        List<Order> eligibleSells = eligible(sells, Side.SELL, clearPrice);
        double qb = sum(eligibleBuys);
        double qs = sum(eligibleSells);
        double volume = Nums.qty(Math.min(qb, qs));

        double dealt = 0;
        if (Nums.isPositive(clearPrice) && Nums.isPositive(volume)) {
            Map<String, Double> buyAlloc = allocate(eligibleBuys, volume, sess.getLotSize());
            Map<String, Double> sellAlloc = allocate(eligibleSells, volume, sess.getLotSize());
            // 申报量大的一侧才是真正主动的一侧，据此给成交染色
            Side taker = Nums.gt(qb, qs) ? Side.BUY : Side.SELL;
            dealt = pairAndExecute(eligibleBuys, buyAlloc, eligibleSells, sellAlloc, clearPrice, taker, sess, sim);
        }

        // 出清后订单簿必须清空：未成交与部分成交的余量一律撤销并解冻
        List<Order> all = new ArrayList<>(buys);
        all.addAll(sells);
        synchronized (book) {
            book.clear();
        }
        for (Order o : all) {
            if (Nums.isPositive(o.remainQty())) {
                o.setStatus(OrderStatus.CANCELLED);
                o.setCanceledAt(System.currentTimeMillis());
                riskService.releaseRemaining(o);
            }
            orderRepo.update(o);
            pushOrder(o);
        }

        if (Nums.isPositive(dealt)) {
            saveClearPrice(sessionId, clearPrice);
        }
        safeBookChanged(sessionId);
        safeAudit(sess.getSimId(), "CALL_AUCTION", sessionId,
                "统一出清价 " + Nums.price(clearPrice) + " 元/MWh，出清电量 " + Nums.qty(dealt) + " MWh");
        log.info("集中竞价出清完成 session={} price={} volume={} orders={}",
                sessionId, clearPrice, dealt, all.size());
    }

    /**
     * 场次快照不是本引擎独占的：每笔成交提交时 MarketPort.onTrade 会自己重读场次、累加
     * totalVolume/totalAmount/lastPrice/high/low/avgPrice 再落盘。若拿出清开始前的旧快照整体
     * 写回，这批统计会被静默清零（丢失更新），所以必须重读最新对象且只落 clearPrice 一个字段。
     * <p>
     * 场次状态机（CLEARING → CLOSED、prevClose 结转）归 TradingSessionService.clear 的
     * finally 收尾所有：引擎抢着置 CLOSED 会让它的闭市分支直接短路返回。
     */
    private void saveClearPrice(String sessionId, double clearPrice) {
        TradingSession latest = simPort.findSession(sessionId);
        if (latest == null) {
            return;
        }
        latest.setClearPrice(Nums.price(clearPrice));
        latest.setUpdatedAt(System.currentTimeMillis());
        simPort.saveSession(latest);
    }

    // ── 分配与配对 ──────────────────────────────────────────

    /**
     * 单侧分配：价格优先逐档全额满足，最后一档不够分时按各单剩余量比例分配（pro-rata），
     * 分配量向下取整到 lotSize，剩下的零头按 seq 升序补给序号最小的订单。
     * 不变量：返回的分配量之和恒等于 volume。
     */
    private Map<String, Double> allocate(List<Order> sorted, double volume, double lotSize) {
        Map<String, Double> alloc = new HashMap<>();
        double remaining = volume;
        int i = 0;
        while (i < sorted.size() && Nums.isPositive(remaining)) {
            double levelPrice = sorted.get(i).getPrice();
            int j = i;
            double levelQty = 0;
            while (j < sorted.size() && Nums.eq(sorted.get(j).getPrice(), levelPrice)) {
                levelQty += sorted.get(j).remainQty();
                j++;
            }
            if (Nums.gte(remaining, levelQty)) {
                for (int k = i; k < j; k++) {
                    alloc.put(sorted.get(k).getId(), Nums.qty(sorted.get(k).remainQty()));
                }
                remaining = Nums.qty(remaining - levelQty);
            } else {
                prorate(sorted.subList(i, j), remaining, levelQty, lotSize, alloc);
                remaining = 0;
            }
            i = j;
        }
        return alloc;
    }

    private void prorate(List<Order> level, double remaining, double levelQty, double lotSize,
                         Map<String, Double> alloc) {
        double lot = lotSize > 0 ? lotSize : 0.001;
        double given = 0;
        for (Order o : level) {
            double raw = remaining * Nums.div(o.remainQty(), levelQty);
            double a = Nums.qty(Math.min(Math.floor(raw / lot + 1e-9) * lot, o.remainQty()));
            alloc.put(o.getId(), a);
            given += a;
        }
        double leftover = Nums.qty(remaining - given);
        if (!Nums.isPositive(leftover)) {
            return;
        }
        List<Order> bySeq = new ArrayList<>(level);
        bySeq.sort(Comparator.comparingLong(Order::getSeq));
        for (Order o : bySeq) {
            if (!Nums.isPositive(leftover)) {
                break;
            }
            double cap = Nums.qty(o.remainQty() - alloc.getOrDefault(o.getId(), 0d));
            if (!Nums.isPositive(cap)) {
                continue;
            }
            double give = Nums.qty(Math.min(cap, leftover));
            alloc.merge(o.getId(), give, Double::sum);
            leftover = Nums.qty(leftover - give);
        }
    }

    private double pairAndExecute(List<Order> buys, Map<String, Double> buyAlloc,
                                  List<Order> sells, Map<String, Double> sellAlloc,
                                  double price, Side takerSide, TradingSession sess, Sim sim) {
        double dealt = 0;
        int bi = 0, si = 0;
        while (bi < buys.size() && si < sells.size()) {
            Order buy = buys.get(bi);
            Order sell = sells.get(si);
            double bq = buyAlloc.getOrDefault(buy.getId(), 0d);
            double sq = sellAlloc.getOrDefault(sell.getId(), 0d);
            if (!Nums.isPositive(bq)) {
                bi++;
                continue;
            }
            if (!Nums.isPositive(sq)) {
                si++;
                continue;
            }
            double q = Nums.qty(Math.min(bq, sq));
            if (!Nums.isPositive(q)) {
                // 残量不足最小计量单位时 q 会被舍成 0，两侧配额与游标都不动，循环将永远出不去。
                // 只丢弃带残渣的那一侧，另一侧的配额必须留给后面的对手单。
                if (bq < sq) {
                    buyAlloc.put(buy.getId(), 0d);
                } else {
                    sellAlloc.put(sell.getId(), 0d);
                }
                continue;
            }
            buyAlloc.put(buy.getId(), Nums.qty(bq - q));
            sellAlloc.put(sell.getId(), Nums.qty(sq - q));

            MatchResult.applyFill(buy, q, price);
            MatchResult.applyFill(sell, q, price);
            try {
                TradingAccount buyer = accountPort.require(buy.getAccountId());
                TradingAccount seller = accountPort.require(sell.getAccountId());
                riskService.releaseForFill(buy, q, sim);
                riskService.releaseForFill(sell, q, sim);
                Trade t = contractService.newTrade(sim, sess.getId(), sess.getVariety(), sess.getPeriodType(),
                        sess.getTradeMode(), buy.getId(), sell.getId(), buyer, seller, price, q, takerSide);
                contractService.commitTrade(t, sess.getDeliveryStart(), sess.getDeliveryEnd());
                dealt = Nums.qty(dealt + q);
            } catch (Exception e) {
                log.error("集中竞价成交提交失败 session={} buy={} sell={}", sess.getId(), buy.getId(), sell.getId(), e);
            }
        }
        return dealt;
    }

    // ── 工具 ────────────────────────────────────────────────

    private List<Order> openOrders(OrderBook book, Side side) {
        List<Order> out = new ArrayList<>();
        for (Order o : book.orders(side)) {
            if (o.getStatus() != null && o.getStatus().isOpen() && Nums.isPositive(o.remainQty())) {
                out.add(o);
            }
        }
        // book.orders 已是价格优先 + 时间优先，此处只是把过滤后的顺序固定下来
        return out;
    }

    private List<Order> eligible(List<Order> orders, Side side, double clearPrice) {
        List<Order> out = new ArrayList<>();
        if (!Nums.isPositive(clearPrice)) {
            return out;
        }
        for (Order o : orders) {
            boolean ok = side == Side.BUY ? Nums.gte(o.getPrice(), clearPrice) : Nums.gte(clearPrice, o.getPrice());
            if (ok) {
                out.add(o);
            }
        }
        return out;
    }

    private double sum(List<Order> orders) {
        double q = 0;
        for (Order o : orders) {
            q += o.remainQty();
        }
        return Nums.qty(q);
    }

    private void pushOrder(Order o) {
        try {
            wsPort.push("order." + o.getAccountId(), o);
        } catch (Exception ignored) {
            // 推送失败不影响出清
        }
    }

    private void safeBookChanged(String sessionId) {
        try {
            marketPort.onBookChanged(sessionId);
        } catch (Exception ignored) {
            // 行情回调失败不影响出清
        }
    }

    private void safeAudit(String simId, String action, String target, String detail) {
        try {
            auditPort.system(simId, action, target, detail);
        } catch (Exception ignored) {
            // 审计失败不影响出清
        }
    }
}
