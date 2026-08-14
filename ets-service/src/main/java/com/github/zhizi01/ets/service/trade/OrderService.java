package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.PlaceOrderCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.engine.MatchResult;
import com.github.zhizi01.ets.engine.OrderBook;
import com.github.zhizi01.ets.engine.OrderBookRegistry;
import com.github.zhizi01.ets.engine.RollingMatchEngine;
import com.github.zhizi01.ets.repo.OrderRepo;
import com.github.zhizi01.ets.repo.TradeRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 申报单生命周期：下单（风控 → 冻结 → 入簿 → 撮合）、撤单、闭市清理与查询。
 * <p>
 * 一致性要点：订单簿里的 Order 实例才是权威副本，撤单必须先从簿里取回实例再改状态，
 * 否则会拿 Redis 里的旧快照覆盖掉刚刚撮合出来的 filledQty。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepo orderRepo;
    private final TradeRepo tradeRepo;
    private final RiskService riskService;
    private final RollingMatchEngine rollingMatchEngine;
    private final OrderBookRegistry registry;
    private final IdGen idGen;

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

    // ── 下单 ────────────────────────────────────────────────

    public Order place(PlaceOrderCmd cmd) {
        if (cmd == null || cmd.getSide() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "买卖方向不能为空");
        }
        TradingSession sess = simPort.requireSession(cmd.getSessionId());
        Sim sim = simPort.requireSim(sess.getSimId());
        TradingAccount acct = accountPort.require(cmd.getAccountId());
        if (sess.getTradeMode() == null || !sess.getTradeMode().usesOrderBook()) {
            String mode = sess.getTradeMode() == null ? "未配置" : sess.getTradeMode().getLabel();
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "「" + mode + "」场次不接受订单簿申报，请使用对应的交易入口");
        }

        Order o = build(cmd, sess, acct);
        riskService.validateAndFreeze(o, acct, sess, sim);
        orderRepo.saveOrder(o);

        if (sess.getState().allowsMatching()) {
            MatchResult r = rollingMatchEngine.submit(o, sess, sim);
            if (r.isRejected()) {
                o.setStatus(OrderStatus.REJECTED);
                o.setRejectReason(r.getRejectReason());
                riskService.releaseRemaining(o);
                orderRepo.update(o);
                pushOrder(o);
                throw BizException.of(ErrorCode.PARAM_INVALID, r.getRejectReason());
            }
        } else {
            // PRE_OPEN：集中申报阶段只收单不撮合
            rollingMatchEngine.enqueue(o, sess);
        }
        return o;
    }

    private Order build(PlaceOrderCmd cmd, TradingSession sess, TradingAccount acct) {
        Order o = new Order();
        o.setId(idGen.next(IdGen.ORDER));
        o.setSimId(sess.getSimId());
        o.setSessionId(sess.getId());
        o.setAccountId(acct.getId());
        o.setUserId(cmd.getUserId());
        o.setSource(cmd.getSource() == null ? OrderSource.HUMAN : cmd.getSource());
        o.setVariety(sess.getVariety());
        o.setPeriodType(sess.getPeriodType());
        o.setTradeMode(sess.getTradeMode());
        o.setSide(cmd.getSide());
        o.setTimeInForce(cmd.getTimeInForce() == null ? TimeInForce.GFD : cmd.getTimeInForce());
        o.setPrice(cmd.getPrice());
        o.setQuantity(cmd.getQuantity());
        o.setStatus(OrderStatus.ACTIVE);
        o.setSeq(idGen.nextSeq());
        o.setCreatedAt(System.currentTimeMillis());
        o.setUpdatedAt(o.getCreatedAt());
        return o;
    }

    // ── 撤单 ────────────────────────────────────────────────

    public void cancel(String orderId, String accountId) {
        Order stored = orderRepo.require(orderId, ErrorCode.ORDER_NOT_FOUND);
        if (accountId != null && !accountId.equals(stored.getAccountId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能撤销自己的申报单");
        }
        TradingSession sess = simPort.requireSession(stored.getSessionId());
        if (!sess.getState().allowsCancel()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次当前为" + sess.getState().getLabel() + "，不允许撤单");
        }
        OrderBook book = registry.book(sess.getId());
        Order o;
        synchronized (book) {
            Order live = book.get(orderId);
            o = live != null ? live : stored;
            if (!o.getStatus().isOpen()) {
                throw BizException.of(ErrorCode.ORDER_NOT_CANCELABLE,
                        "该申报单当前为「" + o.getStatus().getLabel() + "」，不可撤销");
            }
            book.remove(orderId);
            o.setStatus(OrderStatus.CANCELLED);
            o.setCanceledAt(System.currentTimeMillis());
        }
        riskService.releaseRemaining(o);
        orderRepo.update(o);
        pushOrder(o);
        safeBookChanged(sess.getId());
    }

    /** 撤销某账户在某场次的全部挂单，返回实际撤单数。 */
    public int cancelAll(String accountId, String sessionId) {
        TradingSession sess = simPort.requireSession(sessionId);
        OrderBook book = registry.book(sessionId);
        List<Order> victims;
        synchronized (book) {
            victims = new ArrayList<>(book.ordersByAccount(accountId));
            for (Order o : victims) {
                book.remove(o.getId());
                o.setStatus(OrderStatus.CANCELLED);
                o.setCanceledAt(System.currentTimeMillis());
            }
        }
        for (Order o : victims) {
            riskService.releaseRemaining(o);
            orderRepo.update(o);
            pushOrder(o);
        }
        if (!victims.isEmpty()) {
            safeBookChanged(sess.getId());
        }
        return victims.size();
    }

    /** 场次闭市：簿上剩余全部置 EXPIRED 并解冻，随后释放内存簿。 */
    public int expireSessionOrders(String sessionId) {
        OrderBook book = registry.book(sessionId);
        List<Order> resting;
        synchronized (book) {
            resting = new ArrayList<>(book.allOrders());
            book.clear();
        }
        for (Order o : resting) {
            if (o.getStatus().isOpen()) {
                o.setStatus(OrderStatus.EXPIRED);
                o.setCanceledAt(System.currentTimeMillis());
                riskService.releaseRemaining(o);
            }
            orderRepo.update(o);
            pushOrder(o);
        }
        registry.drop(sessionId);
        safeBookChanged(sessionId);
        return resting.size();
    }

    // ── 查询 ────────────────────────────────────────────────

    public List<Order> query(String accountId, String sessionId, OrderStatus status) {
        List<Order> src = accountId != null && !accountId.isBlank()
                ? orderRepo.listByAccount(accountId)
                : orderRepo.listBySession(sessionId);
        List<Order> out = new ArrayList<>();
        for (Order o : src) {
            if (o == null) {
                continue;
            }
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(o.getSessionId())) {
                continue;
            }
            if (status != null && o.getStatus() != status) {
                continue;
            }
            out.add(o);
        }
        out.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return out;
    }

    public List<Trade> queryTrades(String accountId, String sessionId) {
        List<Trade> src = accountId != null && !accountId.isBlank()
                ? tradeRepo.listByAccount(accountId)
                : tradeRepo.listBySession(sessionId);
        List<Trade> out = new ArrayList<>();
        for (Trade t : src) {
            if (t == null) {
                continue;
            }
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(t.getSessionId())) {
                continue;
            }
            out.add(t);
        }
        out.sort((a, b) -> Long.compare(b.getTradedAt(), a.getTradedAt()));
        return out;
    }

    public Order find(String orderId) {
        return orderRepo.find(orderId);
    }

    /** 账户在某场次的未成交冻结额，供组合分析使用。 */
    public double frozenOf(String accountId, String sessionId) {
        double sum = 0;
        for (Order o : registry.book(sessionId).ordersByAccount(accountId)) {
            sum += o.getFrozenAmount();
        }
        return Nums.amount(sum);
    }

    private void pushOrder(Order o) {
        try {
            wsPort.push("order." + o.getAccountId(), o);
        } catch (Exception ignored) {
            // 推送失败不影响下单主流程
        }
    }

    private void safeBookChanged(String sessionId) {
        try {
            marketPort.onBookChanged(sessionId);
        } catch (Exception ignored) {
            // 行情回调失败不影响下单主流程
        }
    }
}
