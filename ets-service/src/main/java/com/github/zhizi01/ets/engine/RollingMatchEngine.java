package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 滚动撮合（连续竞价）。价格优先、时间优先，买价 ≥ 卖价成交，成交价取先到者（Maker）的报价。
 * <p>
 * 并发模型：撮合阶段全程持有该场次 OrderBook 的监视器，保证同一场次严格串行；
 * 但落库、资金结算、行情推送等 I/O 都放在锁外执行——此时成交量已在锁内确定，
 * 不会有第二个线程重复吃掉同一份挂单，因此锁外提交是安全的，也避免了长时间占锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RollingMatchEngine {

    private final OrderBookRegistry registry;
    private final OrderRepo orderRepo;
    private final RiskService riskService;
    private final ContractService contractService;

    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private MarketPort marketPort;
    @Lazy
    @Autowired
    private WsPort wsPort;

    /** 锁内只记录「谁和谁、成交多少、什么价」，Trade 实体留到锁外构造。 */
    private record Fill(Order maker, double quantity, double price) {
    }

    /** 提交一笔申报并立即撮合。调用方需已通过风控与资金冻结。 */
    public MatchResult submit(Order taker, TradingSession sess, Sim sim) {
        OrderBook book = registry.book(sess.getId());
        MatchResult result = new MatchResult();
        result.setSessionId(sess.getId());
        result.setTaker(taker);

        List<Fill> fills = new ArrayList<>();
        Set<Order> touched = new LinkedHashSet<>();
        boolean resting;

        synchronized (book) {
            if (taker.getTimeInForce() == TimeInForce.FOK && !canFillFully(book, taker)) {
                return MatchResult.reject(taker, "FOK 申报无法全额成交，已整单拒绝");
            }
            // 单边快照已按「价格优先 -> 时间优先」排好序，锁内遍历它比反复取 best 更省
            for (Order maker : book.orders(taker.getSide().opposite())) {
                if (!Nums.isPositive(taker.remainQty()) || !crosses(taker, maker)) {
                    break;
                }
                if (isSelf(taker, maker) || !Nums.isPositive(maker.remainQty())) {
                    continue;
                }
                double qty = Nums.qty(Math.min(taker.remainQty(), maker.remainQty()));
                double price = Nums.price(maker.getPrice());
                MatchResult.applyFill(taker, qty, price);
                MatchResult.applyFill(maker, qty, price);
                fills.add(new Fill(maker, qty, price));
                touched.add(maker);
            }
            for (Order m : touched) {
                if (!Nums.isPositive(m.remainQty())) {
                    book.remove(m.getId());
                }
            }
            resting = Nums.isPositive(taker.remainQty()) && !taker.getTimeInForce().isImmediate();
            if (resting) {
                book.add(taker);
            } else if (Nums.isPositive(taker.remainQty())) {
                // IOC / FOK 的剩余电量不留簿，直接撤销
                taker.setStatus(OrderStatus.CANCELLED);
                taker.setCanceledAt(System.currentTimeMillis());
            }
        }

        commit(fills, taker, sess, sim, result);
        if (!resting && Nums.isPositive(taker.remainQty())) {
            riskService.releaseRemaining(taker);
        }

        orderRepo.update(taker);
        pushOrder(taker);
        for (Order m : touched) {
            orderRepo.update(m);
            pushOrder(m);
        }
        safeBookChanged(sess.getId());
        return result;
    }

    /** 集中申报阶段只入簿不撮合。 */
    public void enqueue(Order order, TradingSession sess) {
        registry.book(sess.getId()).add(order);
        orderRepo.update(order);
        pushOrder(order);
        safeBookChanged(sess.getId());
    }

    // ── 内部 ────────────────────────────────────────────────

    private void commit(List<Fill> fills, Order taker, TradingSession sess, Sim sim, MatchResult result) {
        for (Fill f : fills) {
            try {
                Order buy = taker.getSide() == Side.BUY ? taker : f.maker();
                Order sell = taker.getSide() == Side.BUY ? f.maker() : taker;
                TradingAccount buyer = accountPort.require(buy.getAccountId());
                TradingAccount seller = accountPort.require(sell.getAccountId());

                // 先把双方订单级预留放回可用资金，applyTrade 才有钱可扣
                riskService.releaseForFill(buy, f.quantity(), sim);
                riskService.releaseForFill(sell, f.quantity(), sim);

                Trade t = contractService.newTrade(sim, sess.getId(), sess.getVariety(), sess.getPeriodType(),
                        sess.getTradeMode(), buy.getId(), sell.getId(), buyer, seller,
                        f.price(), f.quantity(), taker.getSide());
                contractService.commitTrade(t, sess.getDeliveryStart(), sess.getDeliveryEnd());
                result.add(t);
            } catch (Exception e) {
                log.error("成交提交失败 session={} taker={}", sess.getId(), taker.getId(), e);
            }
        }
    }

    private boolean crosses(Order taker, Order maker) {
        return taker.getSide() == Side.BUY
                ? Nums.gte(taker.getPrice(), maker.getPrice())
                : Nums.gte(maker.getPrice(), taker.getPrice());
    }

    /** 自成交防护：同一账户两侧对敲既会虚增成交量又要白交两遍手续费。 */
    private boolean isSelf(Order taker, Order maker) {
        return taker.getAccountId() != null && taker.getAccountId().equals(maker.getAccountId());
    }

    /** FOK 先试算：对手侧可成交量凑不满全额就整单拒绝，不留任何痕迹。 */
    private boolean canFillFully(OrderBook book, Order taker) {
        double need = taker.remainQty();
        for (Order m : book.orders(taker.getSide().opposite())) {
            if (!crosses(taker, m)) {
                break;
            }
            if (isSelf(taker, m)) {
                continue;
            }
            need -= m.remainQty();
            if (!Nums.isPositive(need)) {
                return true;
            }
        }
        return !Nums.isPositive(need);
    }

    private void pushOrder(Order o) {
        try {
            wsPort.push("order." + o.getAccountId(), o);
        } catch (Exception ignored) {
            // 推送失败不影响撮合
        }
    }

    private void safeBookChanged(String sessionId) {
        try {
            marketPort.onBookChanged(sessionId);
        } catch (Exception ignored) {
            // 行情回调失败不影响撮合
        }
    }
}
