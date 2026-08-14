package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.dto.NegoCounterpartyVO;
import com.github.zhizi01.ets.domain.enums.NegoStatus;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.model.NegoRound;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.engine.BilateralEngine;
import com.github.zhizi01.ets.repo.NegotiationRepo;
import com.github.zhizi01.ets.repo.RedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 双边协商的状态机：发起 → 对手方 接受 / 还价 / 拒绝，轮流进行，最多 MAX_ROUNDS 轮。
 * <p>
 * 轮次由 currentTurnAccountId 唯一决定：只有它指向的账户能做动作，其余一律 NEGO_NOT_YOUR_TURN。
 * 撤回是「收回自己挂出去的报价」，因此只允许在不是自己轮次时执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NegotiationService {

    private static final int DEFAULT_EXPIRE_MINUTES = 30;
    /** 机器人自动应答的扫描周期 */
    private static final long ROBOT_TICK_MS = 4000;

    private final NegotiationRepo negotiationRepo;
    private final BilateralEngine bilateralEngine;
    private final RiskService riskService;
    private final RedisStore store;
    private final IdGen idGen;
    private final EtsProperties props;

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

    // ── 用户动作 ────────────────────────────────────────────

    /**
     * 发起邀约。真人（REST）与机器人（TradePort）走的是这唯一一条路径，
     * 区别只在于 source=ROBOT 时账户不属于任何 userId，跳过归属校验。
     */
    public Negotiation propose(String accountId, String counterpartyAccountId, String sessionId, Side side,
                               double price, double quantity, String deliveryStart, String deliveryEnd,
                               String message, Integer expireMinutes, OrderSource source, String userId) {
        if (accountId == null || counterpartyAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "协商双方账户不能为空");
        }
        if (accountId.equals(counterpartyAccountId)) {
            throw BizException.of(ErrorCode.NEGO_SELF);
        }
        if (side == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "协商方向不能为空");
        }
        TradingSession sess = simPort.requireSession(sessionId);
        Sim sim = simPort.requireSim(sess.getSimId());
        if (sess.getTradeMode() != TradeMode.BILATERAL) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "「" + sess.getTradeMode().getLabel() + "」场次不支持双边协商");
        }
        TradingAccount me = accountPort.require(accountId);
        TradingAccount other = accountPort.require(counterpartyAccountId);
        if (!me.getSimId().equals(other.getSimId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "协商双方必须属于同一个模拟盘");
        }

        // 只做「能不能谈」的校验，资金与可售电量留到 ACCEPTED 真正成交时再卡
        checkTradable(sim, sess, me, source == OrderSource.ROBOT ? null : userId);
        checkTradable(sim, sess, other, null);
        double p = riskService.normalizePrice(price, sess);
        double q = Nums.qty(quantity);
        riskService.checkPrice(p, sess, sim);
        riskService.checkQuantity(q, sess, me);

        long now = System.currentTimeMillis();
        int minutes = expireMinutes == null || expireMinutes <= 0 ? DEFAULT_EXPIRE_MINUTES : expireMinutes;

        Negotiation n = new Negotiation();
        n.setId(idGen.next(IdGen.NEGO));
        n.setSimId(sess.getSimId());
        n.setSessionId(sessionId);
        n.setVariety(sess.getVariety());
        n.setPeriodType(sess.getPeriodType());
        n.setInitiatorAccountId(me.getId());
        n.setInitiatorAccountCode(me.getCode());
        n.setInitiatorAccountName(me.getName());
        n.setInitiatorUserId(me.getUserId());
        n.setCounterpartyAccountId(other.getId());
        n.setCounterpartyAccountCode(other.getCode());
        n.setCounterpartyAccountName(other.getName());
        n.setCounterpartyUserId(other.getUserId());
        n.setInitiatorSide(side);
        n.setPrice(p);
        n.setQuantity(q);
        n.setDeliveryStart(deliveryStart != null ? deliveryStart : sess.getDeliveryStart());
        n.setDeliveryEnd(deliveryEnd != null ? deliveryEnd : sess.getDeliveryEnd());
        n.setStatus(NegoStatus.PROPOSED);
        n.setCurrentTurnAccountId(other.getId());
        n.setExpireAt(now + minutes * 60_000L);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        addRound(n, me, p, q, message, "PROPOSE");

        negotiationRepo.saveNego(n);
        pushBoth(n);
        return n;
    }

    public Negotiation counter(String negoId, String accountId, double price, double quantity, String message) {
        Negotiation n = requireActionable(negoId, accountId);
        if (n.roundCount() >= Negotiation.MAX_ROUNDS) {
            throw BizException.of(ErrorCode.NEGO_ROUND_EXCEEDED,
                    "协商轮次已达上限 " + Negotiation.MAX_ROUNDS + " 轮，请直接接受或拒绝");
        }
        TradingAccount actor = accountPort.require(accountId);
        double p = Nums.price(price);
        double q = quantity > 0 ? Nums.qty(quantity) : n.getQuantity();
        if (!Nums.isPositive(p)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "还价价格必须大于 0");
        }
        n.setPrice(p);
        n.setQuantity(q);
        n.setStatus(NegoStatus.COUNTERED);
        n.setCurrentTurnAccountId(opposite(n, accountId));
        addRound(n, actor, p, q, message, "COUNTER");
        negotiationRepo.update(n);
        pushBoth(n);
        return n;
    }

    public Negotiation accept(String negoId, String accountId) {
        Negotiation n = requireActionable(negoId, accountId);
        TradingAccount actor = accountPort.require(accountId);
        // 成交失败（资金/电量不足）时状态保持不变，允许对方继续还价
        bilateralEngine.execute(n, accountId);
        n.setStatus(NegoStatus.ACCEPTED);
        n.setCurrentTurnAccountId(null);
        addRound(n, actor, n.getPrice(), n.getQuantity(), null, "ACCEPT");
        negotiationRepo.update(n);
        pushBoth(n);
        return n;
    }

    public Negotiation reject(String negoId, String accountId, String message) {
        Negotiation n = requireActionable(negoId, accountId);
        TradingAccount actor = accountPort.require(accountId);
        n.setStatus(NegoStatus.REJECTED);
        n.setCurrentTurnAccountId(null);
        addRound(n, actor, n.getPrice(), n.getQuantity(), message, "REJECT");
        negotiationRepo.update(n);
        pushBoth(n);
        return n;
    }

    public Negotiation withdraw(String negoId, String accountId) {
        Negotiation n = negotiationRepo.require(negoId, ErrorCode.NEGO_NOT_FOUND);
        requireParticipant(n, accountId);
        if (!n.getStatus().isActive()) {
            throw BizException.of(ErrorCode.NEGO_STATE_ILLEGAL,
                    "协商当前为「" + n.getStatus().getLabel() + "」，不能撤回");
        }
        if (accountId.equals(n.getCurrentTurnAccountId())) {
            throw BizException.of(ErrorCode.NEGO_STATE_ILLEGAL, "当前轮到您回应，请选择接受或拒绝");
        }
        TradingAccount actor = accountPort.require(accountId);
        n.setStatus(NegoStatus.WITHDRAWN);
        n.setCurrentTurnAccountId(null);
        addRound(n, actor, n.getPrice(), n.getQuantity(), null, "WITHDRAW");
        negotiationRepo.update(n);
        pushBoth(n);
        return n;
    }

    // ── 查询 ────────────────────────────────────────────────

    public Negotiation detail(String negoId) {
        Negotiation n = negotiationRepo.require(negoId, ErrorCode.NEGO_NOT_FOUND);
        return sweepExpired(n);
    }

    /** role: all | initiator | counterparty */
    public List<Negotiation> query(String accountId, String role, NegoStatus status) {
        List<Negotiation> out = new ArrayList<>();
        for (Negotiation n : negotiationRepo.listByAccount(accountId)) {
            if (n == null) {
                continue;
            }
            if ("initiator".equalsIgnoreCase(role) && !accountId.equals(n.getInitiatorAccountId())) {
                continue;
            }
            if ("counterparty".equalsIgnoreCase(role) && !accountId.equals(n.getCounterpartyAccountId())) {
                continue;
            }
            Negotiation swept = sweepExpired(n);
            if (status != null && swept.getStatus() != status) {
                continue;
            }
            out.add(swept);
        }
        out.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return out;
    }

    /** 可协商对手方：同一模拟盘下除自己以外的全部账户。 */
    public List<NegoCounterpartyVO> counterparties(String simId, String accountId) {
        List<NegoCounterpartyVO> out = new ArrayList<>();
        for (TradingAccount a : accountPort.listBySim(simId)) {
            if (a == null || a.getId().equals(accountId)) {
                continue;
            }
            out.add(NegoCounterpartyVO.of(a));
        }
        out.sort((x, y) -> String.valueOf(x.getCode()).compareTo(String.valueOf(y.getCode())));
        return out;
    }

    /** 场次闭市：把该场次仍在进行中的协商全部置为过期。 */
    public int expireBySession(String sessionId) {
        int n = 0;
        Set<String> seen = new HashSet<>();
        TradingSession sess = simPort.findSession(sessionId);
        if (sess == null) {
            return 0;
        }
        for (TradingAccount a : accountPort.listBySim(sess.getSimId())) {
            for (Negotiation neg : negotiationRepo.listByAccount(a.getId())) {
                if (neg == null || !neg.getStatus().isActive() || !sessionId.equals(neg.getSessionId())) {
                    continue;
                }
                if (seen.add(neg.getId())) {
                    expire(neg);
                    n++;
                }
            }
        }
        return n;
    }

    // ── 机器人自动应答 ──────────────────────────────────────

    /**
     * 每 4 秒扫描一次：把过期协商收掉，并替轮到机器人账户回应的协商自动作答。
     * 全局锁保证多实例部署时只有一个节点在跑，避免同一笔协商被回应两次。
     */
    @Scheduled(fixedDelay = ROBOT_TICK_MS)
    public void robotAutoRespond() {
        if (!props.getRobot().isEnabled()) {
            return;
        }
        try {
            store.withLock("nego:auto", Duration.ofSeconds(10), 100, () -> {
                sweepAll();
                return Boolean.TRUE;
            });
        } catch (Exception e) {
            log.warn("协商自动应答任务异常: {}", e.getMessage());
        }
    }

    private void sweepAll() {
        long now = System.currentTimeMillis();
        for (Sim sim : simPort.listRunning()) {
            Map<String, TradingAccount> accounts = new HashMap<>();
            for (TradingAccount a : accountPort.listBySim(sim.getId())) {
                accounts.put(a.getId(), a);
            }
            Set<String> seen = new HashSet<>();
            for (TradingAccount a : accounts.values()) {
                // 绝大多数账户没有协商，先用一次 ZCARD 挡掉，避免每 4 秒全量 MGET
                if (negotiationRepo.countByAccount(a.getId()) == 0) {
                    continue;
                }
                for (Negotiation n : negotiationRepo.listByAccount(a.getId())) {
                    if (n == null || !n.getStatus().isActive() || !seen.add(n.getId())) {
                        continue;
                    }
                    if (n.getExpireAt() > 0 && now >= n.getExpireAt()) {
                        expire(n);
                        continue;
                    }
                    TradingAccount turn = accounts.get(n.getCurrentTurnAccountId());
                    if (turn != null && turn.getSource() == OrderSource.ROBOT) {
                        respondAsRobot(n, turn, sim);
                    }
                }
            }
        }
    }

    /**
     * 机器人策略：报价对自己有利就接受（买方看 ≤ 参考价×1.02，卖方看 ≥ 参考价×0.98），
     * 否则朝有利方向还价 1~3%；轮次快用尽时放宽到 ±5%，还不满意就拒绝。
     */
    private void respondAsRobot(Negotiation n, TradingAccount robot, Sim sim) {
        try {
            Side mySide = n.sideOf(robot.getId());
            if (mySide == null) {
                return;
            }
            double ref = referencePrice(n, sim);
            double price = n.getPrice();
            boolean favorable = mySide == Side.BUY
                    ? Nums.gte(ref * 1.02, price)
                    : Nums.gte(price, ref * 0.98);
            boolean lastCall = n.roundCount() >= Negotiation.MAX_ROUNDS - 1;

            if (favorable) {
                acceptQuietly(n, robot);
                return;
            }
            if (lastCall) {
                boolean tolerable = mySide == Side.BUY
                        ? Nums.gte(ref * 1.05, price)
                        : Nums.gte(price, ref * 0.95);
                if (tolerable) {
                    acceptQuietly(n, robot);
                } else {
                    reject(n.getId(), robot.getId(), "报价与市场参考价差距过大，暂不接受");
                }
                return;
            }
            double delta = 0.01 + ThreadLocalRandom.current().nextDouble() * 0.02;
            double target = mySide == Side.BUY ? price * (1 - delta) : price * (1 + delta);
            counter(n.getId(), robot.getId(), clampToSession(n, target), n.getQuantity(), "机器人自动还价");
        } catch (BizException e) {
            log.debug("机器人应答被拒 nego={}: {}", n.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("机器人应答失败 nego={}", n.getId(), e);
        }
    }

    /** 接受失败（多半是资金或可售电量不够）时改为拒绝，免得协商一直卡在机器人这一轮。 */
    private void acceptQuietly(Negotiation n, TradingAccount robot) {
        try {
            accept(n.getId(), robot.getId());
        } catch (Exception e) {
            log.debug("机器人接受失败转为拒绝 nego={}: {}", n.getId(), e.getMessage());
            try {
                reject(n.getId(), robot.getId(), "履约能力不足，无法达成");
            } catch (Exception ignored) {
                // 状态可能已被对手方改变，忽略
            }
        }
    }

    private double referencePrice(Negotiation n, Sim sim) {
        double ref = 0;
        try {
            ref = marketPort.referencePrice(sim.getId(),
                    TradingSession.instrumentKey(n.getVariety(), n.getPeriodType()));
        } catch (Exception ignored) {
            // 行情尚未初始化时退化为基准电价
        }
        if (!Nums.isPositive(ref)) {
            PeriodType pt = n.getPeriodType() == null ? PeriodType.ALL : n.getPeriodType();
            ref = sim.getCfg().getBenchmarkPrice() * pt.getPriceFactor();
        }
        return Nums.price(ref);
    }

    private double clampToSession(Negotiation n, double price) {
        TradingSession sess = n.getSessionId() == null ? null : simPort.findSession(n.getSessionId());
        if (sess == null) {
            return Nums.price(price);
        }
        return Nums.alignTick(Nums.clamp(price, sess.getPriceFloor(), sess.getPriceCap()), sess.getTickSize());
    }

    // ── 内部 ────────────────────────────────────────────────

    private Negotiation requireActionable(String negoId, String accountId) {
        Negotiation n = negotiationRepo.require(negoId, ErrorCode.NEGO_NOT_FOUND);
        requireParticipant(n, accountId);
        if (!n.getStatus().isActive()) {
            throw BizException.of(ErrorCode.NEGO_STATE_ILLEGAL,
                    "协商当前为「" + n.getStatus().getLabel() + "」，不能继续操作");
        }
        if (n.getExpireAt() > 0 && System.currentTimeMillis() >= n.getExpireAt()) {
            expire(n);
            throw BizException.of(ErrorCode.NEGO_STATE_ILLEGAL, "本次协商已超时失效");
        }
        if (!accountId.equals(n.getCurrentTurnAccountId())) {
            throw BizException.of(ErrorCode.NEGO_NOT_YOUR_TURN);
        }
        return n;
    }

    private void requireParticipant(Negotiation n, String accountId) {
        if (accountId == null
                || (!accountId.equals(n.getInitiatorAccountId()) && !accountId.equals(n.getCounterpartyAccountId()))) {
            throw BizException.of(ErrorCode.FORBIDDEN, "您不是本次协商的参与方");
        }
    }

    /** userId 为 null 表示校验对手方，只查可交易性不查归属。 */
    private void checkTradable(Sim sim, TradingSession sess, TradingAccount acct, String userId) {
        riskService.checkSimRunning(sim);
        riskService.checkSessionAcceptsOrders(sess);
        riskService.checkAccountTradable(acct, sess);
        riskService.checkOwnership(acct, userId);
    }

    private String opposite(Negotiation n, String accountId) {
        return accountId.equals(n.getInitiatorAccountId())
                ? n.getCounterpartyAccountId() : n.getInitiatorAccountId();
    }

    private void addRound(Negotiation n, TradingAccount actor, double price, double qty,
                          String message, String action) {
        NegoRound r = new NegoRound();
        r.setRound(n.roundCount() + 1);
        r.setByAccountId(actor.getId());
        r.setByAccountCode(actor.getCode());
        r.setPrice(Nums.price(price));
        r.setQuantity(Nums.qty(qty));
        r.setMessage(message);
        r.setAction(action);
        r.setAt(System.currentTimeMillis());
        n.getRounds().add(r);
        n.setUpdatedAt(r.getAt());
    }

    private Negotiation sweepExpired(Negotiation n) {
        if (n.getStatus().isActive() && n.getExpireAt() > 0 && System.currentTimeMillis() >= n.getExpireAt()) {
            expire(n);
        }
        return n;
    }

    private void expire(Negotiation n) {
        n.setStatus(NegoStatus.EXPIRED);
        n.setCurrentTurnAccountId(null);
        negotiationRepo.update(n);
        pushBoth(n);
    }

    private void pushBoth(Negotiation n) {
        safePush("nego." + n.getInitiatorAccountId(), n);
        safePush("nego." + n.getCounterpartyAccountId(), n);
    }

    private void safePush(String topic, Object data) {
        try {
            wsPort.push(topic, data);
        } catch (Exception ignored) {
            // 推送失败不影响协商主流程
        }
    }
}
