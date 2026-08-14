package com.github.zhizi01.ets.robot;

import com.github.zhizi01.ets.api.CreateListingCmd;
import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.PlaceOrderCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.TradePort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.ListingRepo;
import com.github.zhizi01.ets.repo.OrderRepo;
import com.github.zhizi01.ets.repo.RobotRepo;
import com.github.zhizi01.ets.robot.strategy.RobotStrategyHandler;
import com.github.zhizi01.ets.service.market.FundamentalService;
import com.github.zhizi01.ets.service.market.KlineService;
import com.github.zhizi01.ets.ws.WsTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个机器人一次 tick 的行为编排。
 * <p>
 * 每个 tick 会依次考虑：撤旧单 → 挂牌/摘牌 → 现货曲线申报 → 按策略在订单簿下单，
 * 每一步都按配置的概率触发，这样同一批机器人跑出来的动作序列各不相同，盘面才有真实感。
 * <p>
 * TradePort 抛 BizException 是常态（风控拒绝、场次刚闭市、资金不足），
 * 这里一律吞掉记 debug，绝不能让某一步的异常打断整个 tick 循环。
 */
@Slf4j
@Component
public class RobotRunner {

    /** 每个机器人最多记住这么多自己的挂单 id，用于随机撤单 */
    private static final int ORDER_MEMORY = 60;
    /** 资金/电量用满会频繁被拒，只用到八成留出缓冲 */
    private static final double RESOURCE_USAGE = 0.8;
    /**
     * 已经报过曲线的现货场次，每个 tick 重报一次的概率。
     * submitSpotBid 是覆盖语义，重报不会产生脏数据，但 24 段曲线的读改写成本不低，
     * 所以首次必报、之后只做低频微调。
     */
    private static final double SPOT_REFRESH_P = 0.05;
    /** 满足最小间隔后，每个 tick 真正发起协商的概率，避免 24 台机器人整点齐刷 */
    private static final double NEGO_P = 0.5;

    private final TradePort tradePort;
    private final MarketPort marketPort;
    private final SimPort simPort;
    private final AccountPort accountPort;
    private final RobotRepo robotRepo;
    private final OrderRepo orderRepo;
    private final ListingRepo listingRepo;
    private final KlineService klineService;
    private final FundamentalService fundamentalService;
    private final WsPort wsPort;
    private final SpotCurveBuilder spotCurveBuilder;
    private final NegotiationInitiator negotiationInitiator;
    private final Map<RobotStrategy, RobotStrategyHandler> handlers = new EnumMap<>(RobotStrategy.class);

    public RobotRunner(@Lazy TradePort tradePort,
                       MarketPort marketPort,
                       @Lazy SimPort simPort,
                       @Lazy AccountPort accountPort,
                       RobotRepo robotRepo,
                       OrderRepo orderRepo,
                       ListingRepo listingRepo,
                       KlineService klineService,
                       FundamentalService fundamentalService,
                       WsPort wsPort,
                       SpotCurveBuilder spotCurveBuilder,
                       NegotiationInitiator negotiationInitiator,
                       List<RobotStrategyHandler> strategyHandlers) {
        this.tradePort = tradePort;
        this.marketPort = marketPort;
        this.simPort = simPort;
        this.accountPort = accountPort;
        this.robotRepo = robotRepo;
        this.orderRepo = orderRepo;
        this.listingRepo = listingRepo;
        this.klineService = klineService;
        this.fundamentalService = fundamentalService;
        this.wsPort = wsPort;
        this.spotCurveBuilder = spotCurveBuilder;
        this.negotiationInitiator = negotiationInitiator;
        for (RobotStrategyHandler h : strategyHandlers) {
            handlers.put(h.strategy(), h);
        }
        log.info("机器人策略已装载: {}", handlers.keySet());
    }

    /** 每个机器人的私有运行态，由引擎持有 */
    public static class State {
        private final String robotId;
        private final Random rnd;
        private final Deque<String> myOrders = new ArrayDeque<>();
        /** 已经成功报过曲线的现货场次，用来把首报与后续微调区分开 */
        private final Set<String> spotDone = ConcurrentHashMap.newKeySet();
        private boolean seeded;
        /** 上次发起双边邀约的时刻，频控用 */
        private long lastNegoAt;
        /** 每次 tick 回写一次，管理员改了 cfg 下一轮调度就会生效 */
        private volatile long tickIntervalMs;

        State(String robotId, long seed, long tickIntervalMs) {
            this.robotId = robotId;
            this.rnd = new Random(seed == 0 ? System.nanoTime() : seed);
            this.tickIntervalMs = tickIntervalMs;
        }

        public String robotId() {
            return robotId;
        }

        public long tickIntervalMs() {
            return tickIntervalMs;
        }

        boolean negoReady(long now) {
            return now - lastNegoAt >= NegotiationInitiator.MIN_INTERVAL_MS;
        }
    }

    public State newState(Robot r) {
        RobotConfig cfg = r.getCfg() == null ? new RobotConfig() : r.getCfg();
        return new State(r.getId(), cfg.getRandomSeed(), cfg.getTickIntervalMs());
    }

    /** 本次 tick 的行为统计，结束后一次性回写机器人实体 */
    private static class Tally {
        int orders;
        int cancels;
        int listings;
        int takes;
        int spots;
        int negos;
        String lastAction;

        boolean any() {
            return orders + cancels + listings + takes + spots + negos > 0;
        }
    }

    // ── tick ────────────────────────────────────────────────

    /** 返回 false 表示这个机器人已经不该继续跑（被删除、被禁用、模拟盘结束） */
    public boolean tick(State st) {
        Robot r = robotRepo.find(st.robotId);
        if (r == null) {
            return false;
        }
        if (r.getCfg() != null && r.getCfg().getTickIntervalMs() > 0) {
            st.tickIntervalMs = r.getCfg().getTickIntervalMs();
        }
        if (!r.isEnabled()) {
            return true;
        }
        Sim sim = simPort.findSim(r.getSimId());
        if (sim == null || sim.getState() == SimState.ENDED) {
            return false;
        }
        // PAUSED 时保留 tick 循环但不动作，管理员点恢复就能立刻接上
        if (sim.getState() != SimState.RUNNING) {
            return true;
        }
        TradingAccount acct = accountPort.find(r.getAccountId());
        if (acct == null || acct.isFrozen()) {
            return true;
        }
        List<TradingSession> open = openSessions(sim.getId());
        if (open.isEmpty()) {
            return true;
        }

        RobotConfig cfg = r.getCfg() == null ? new RobotConfig() : r.getCfg();
        Random rnd = st.rnd;
        Tally tally = new Tally();
        seedOrderMemory(st, acct);

        List<TradingSession> book = new ArrayList<>();
        List<TradingSession> listings = new ArrayList<>();
        List<TradingSession> spots = new ArrayList<>();
        List<TradingSession> bilaterals = new ArrayList<>();
        for (TradingSession s : open) {
            TradeMode mode = s.getTradeMode();
            if (mode == null) {
                continue;
            }
            if (mode.usesOrderBook()) {
                book.add(s);
            } else if (mode == TradeMode.LISTING) {
                listings.add(s);
            } else if (mode == TradeMode.SPOT_CLEARING) {
                spots.add(s);
            } else if (mode == TradeMode.BILATERAL) {
                bilaterals.add(s);
            }
        }

        if (!st.myOrders.isEmpty() && rnd.nextDouble() < cfg.getCancelProbability()) {
            cancelOne(st, acct, tally);
        }
        if (!listings.isEmpty() && rnd.nextDouble() < cfg.getListingProbability()) {
            TradingSession s = pick(listings, rnd);
            if (rnd.nextDouble() < 0.45) {
                takeListing(r, acct, sim, s, cfg, rnd, tally);
            } else {
                createListing(r, acct, sim, s, cfg, rnd, tally);
            }
        }
        // 现货曲线每个场次都要覆盖到，否则前端的「日前现货」页面会缺边
        for (TradingSession s : spots) {
            boolean first = !st.spotDone.contains(s.getId());
            if ((first || rnd.nextDouble() < SPOT_REFRESH_P)
                    && submitSpot(r, acct, sim, s, cfg, rnd, tally)) {
                st.spotDone.add(s.getId());
            }
        }
        long now = System.currentTimeMillis();
        if (!bilaterals.isEmpty() && st.negoReady(now) && rnd.nextDouble() < NEGO_P) {
            if (proposeNego(r, acct, sim, pick(bilaterals, rnd), cfg, st, tally)) {
                st.lastNegoAt = now;
            } else {
                // 没发起成功也压一段冷却，避免每个 tick 都去翻一遍协商列表
                st.lastNegoAt = now - NegotiationInitiator.MIN_INTERVAL_MS / 2;
            }
        }
        if (!book.isEmpty() && rnd.nextDouble() < cfg.getActProbability()) {
            runStrategy(r, acct, sim, pick(book, rnd), st, cfg, tally);
        }

        writeBack(r, acct, tally);
        return true;
    }

    // ── 订单簿下单 ──────────────────────────────────────────

    private void runStrategy(Robot r, TradingAccount acct, Sim sim, TradingSession s,
                             State st, RobotConfig cfg, Tally tally) {
        RobotStrategyHandler handler = handlers.get(r.getStrategy());
        if (handler == null) {
            return;
        }
        RobotStrategyHandler.Ctx c = new RobotStrategyHandler.Ctx();
        c.setRobot(r);
        c.setCfg(cfg);
        c.setAccount(acct);
        c.setMemberType(acct.getMemberType() != null ? acct.getMemberType() : r.getMemberType());
        c.setSim(sim);
        c.setSession(s);
        c.setFundamental(fundamentalService.get(sim.getId()));
        c.setReference(marketPort.referencePrice(sim.getId(), s.instrumentKey()));
        c.setRnd(st.rnd);
        c.setOps(new OpsImpl(r, acct, sim, s, cfg, st, tally));

        // 集中申报阶段盘口对所有人保密，机器人同样不能偷看，只能围绕参考价报
        if (s.getState() == TSessionState.PRE_OPEN) {
            c.setMid(0);
        } else {
            Depth d = marketPort.depth(s.getId(), 1);
            if (d != null && !d.isHidden()) {
                if (!d.getBids().isEmpty()) {
                    c.setBestBid(d.getBids().get(0).getPrice());
                }
                if (!d.getAsks().isEmpty()) {
                    c.setBestAsk(d.getAsks().get(0).getPrice());
                }
            }
            c.setMid(marketPort.midPrice(s.getId()));
        }

        try {
            handler.act(c);
        } catch (BizException e) {
            log.debug("机器人 {} 策略被拒: {}", r.getName(), e.getMessage());
        } catch (Exception e) {
            log.debug("机器人 {} 策略异常: {}", r.getName(), e.toString());
        }
    }

    // ── 撤单 ────────────────────────────────────────────────

    private void cancelOne(State st, TradingAccount acct, Tally tally) {
        String orderId = st.myOrders.pollFirst();
        if (orderId == null) {
            return;
        }
        try {
            tradePort.cancelOrder(orderId, acct.getId());
            tally.cancels++;
            tally.lastAction = "撤单 " + orderId;
        } catch (BizException e) {
            // 单子早就成交或已撤，从记忆里丢掉即可
            log.debug("撤单被拒 {}: {}", orderId, e.getMessage());
        } catch (Exception e) {
            log.debug("撤单异常 {}: {}", orderId, e.getMessage());
        }
    }

    // ── 挂牌 / 摘牌 ─────────────────────────────────────────

    private void createListing(Robot r, TradingAccount acct, Sim sim, TradingSession s,
                               RobotConfig cfg, Random rnd, Tally tally) {
        MemberType mt = memberTypeOf(r, acct);
        Side side = mt == MemberType.GENCO ? Side.SELL
                : (mt == MemberType.CONSUMER ? Side.BUY : (rnd.nextBoolean() ? Side.BUY : Side.SELL));
        OpsImpl ops = new OpsImpl(r, acct, sim, s, cfg, null, tally);

        double ref = marketPort.referencePrice(sim.getId(), s.instrumentKey());
        double away = 0.004 + rnd.nextDouble() * 0.020;
        double price = ops.fixPrice(side == Side.SELL ? ref * (1 + away) : ref * (1 - away));
        // 挂牌是大宗协议式交易，单量比连续竞价的申报单大
        double qty = ops.capByRisk(side, price, ops.fixQty(cfg.getMaxOrderMwh() * (1.2 + rnd.nextDouble() * 1.8)));
        if (price <= 0 || qty <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long validUntil = now + (10L + rnd.nextInt(30)) * 60_000L;
        if (s.getCloseTime() > now) {
            validUntil = Math.min(validUntil, s.getCloseTime());
        }
        boolean allowPartial = rnd.nextDouble() < 0.75;
        // 门槛定太高会让绝大多数对手方够不着，挂牌永远摘不掉；
        // 默认只要求场次最小申报量，少量挂牌才设整块门槛
        double floorQty = Math.max(Math.max(s.getMinQty(), s.getLotSize()), 1);
        double minTake = qty;
        if (allowPartial) {
            minTake = rnd.nextDouble() < 0.25 ? Nums.qty(Math.max(qty * 0.25, floorQty)) : floorQty;
        }

        try {
            Listing l = tradePort.createListing(CreateListingCmd.builder()
                    .accountId(acct.getId())
                    .sessionId(s.getId())
                    .side(side)
                    .price(price)
                    .totalQty(qty)
                    .minTakeQty(Math.min(minTake, qty))
                    .allowPartial(allowPartial)
                    .validUntil(validUntil)
                    .title(listingTitle(s, side, qty))
                    .remark(r.getName() + " 发布")
                    .source(OrderSource.ROBOT)
                    .build());
            if (l != null) {
                tally.listings++;
                tally.lastAction = (side == Side.SELL ? "挂牌售电 " : "挂牌购电 ") + Nums.price(price);
                wsPort.push(WsTopics.listing(sim.getId(), s.instrumentKey()), "listing", l);
            }
        } catch (BizException e) {
            log.debug("机器人 {} 挂牌被拒: {}", r.getName(), e.getMessage());
        } catch (Exception e) {
            log.debug("机器人 {} 挂牌异常: {}", r.getName(), e.toString());
        }
    }

    private void takeListing(Robot r, TradingAccount acct, Sim sim, TradingSession s,
                             RobotConfig cfg, Random rnd, Tally tally) {
        List<Listing> candidates;
        try {
            candidates = listingRepo.recentBySession(s.getId(), 40);
        } catch (Exception e) {
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        MemberType mt = memberTypeOf(r, acct);
        OpsImpl ops = new OpsImpl(r, acct, sim, s, cfg, null, tally);
        long now = System.currentTimeMillis();
        double ref = marketPort.referencePrice(sim.getId(), s.instrumentKey());

        Listing best = null;
        for (Listing l : candidates) {
            if (l == null || l.getStatus() == null || !l.getStatus().isTakeable()
                    || l.expired(now) || l.getRemainQty() <= 1e-9 || l.getSide() == null) {
                continue;
            }
            if (acct.getId().equals(l.getAccountId())) {
                continue;
            }
            // 摘牌方向与挂牌方向相反
            Side mySide = l.getSide().opposite();
            if (mySide == Side.SELL && mt == MemberType.CONSUMER) {
                continue;
            }
            if (!priceAcceptable(mySide, l.getPrice(), ref, acct, cfg)) {
                continue;
            }
            if (best == null
                    || (mySide == Side.BUY && l.getPrice() < best.getPrice())
                    || (mySide == Side.SELL && l.getPrice() > best.getPrice())) {
                best = l;
            }
        }
        if (best == null) {
            return;
        }

        // 摘牌量只受挂牌方的规则约束（minTakeQty / remainQty / 是否允许部分摘牌），
        // 不走场次的最小申报量，所以这里单独算，凑不满就干脆放弃
        Side mySide = best.getSide().opposite();
        double remain = Nums.qty(best.getRemainQty());
        double minTake = Nums.qty(Math.max(best.getMinTakeQty(), 0));
        double capacity = ops.capacity(mySide, best.getPrice());
        double qty;
        if (best.isAllowPartial()) {
            double want = Math.max(minTake, remain * (0.3 + 0.7 * rnd.nextDouble()));
            qty = Nums.qty(Math.min(Math.min(want, remain), capacity));
            if (qty + 1e-9 < minTake) {
                return;
            }
        } else {
            if (capacity + 1e-9 < remain) {
                return;
            }
            qty = remain;
        }
        if (qty <= 0 || qty > remain + 1e-9) {
            return;
        }

        try {
            tradePort.takeListing(best.getId(), acct.getId(), qty, OrderSource.ROBOT);
            tally.takes++;
            tally.lastAction = (mySide == Side.BUY ? "摘牌购电 " : "摘牌售电 ") + Nums.price(best.getPrice());
            wsPort.push(WsTopics.listing(sim.getId(), s.instrumentKey()), "taken", best);
        } catch (BizException e) {
            log.debug("机器人 {} 摘牌被拒: {}", r.getName(), e.getMessage());
        } catch (Exception e) {
            log.debug("机器人 {} 摘牌异常: {}", r.getName(), e.toString());
        }
    }

    private boolean priceAcceptable(Side mySide, double price, double ref, TradingAccount acct, RobotConfig cfg) {
        if (price <= 0) {
            return false;
        }
        AccountParams p = acct.getParams();
        if (mySide == Side.BUY) {
            double cap = ref * 1.06;
            if (p != null && p.getMaxAcceptPricePerMwh() != null && p.getMaxAcceptPricePerMwh() > 0) {
                cap = Math.min(Math.max(cap, ref), p.getMaxAcceptPricePerMwh());
            }
            return price <= cap;
        }
        double floor = ref * 0.94;
        if (p != null && p.getVariableCostPerMwh() != null && p.getVariableCostPerMwh() > 0) {
            floor = Math.max(floor, p.getVariableCostPerMwh() * 1.02);
        }
        return price >= floor;
    }

    private String listingTitle(TradingSession s, Side side, double qty) {
        String variety = s.getVariety() == null ? "" : s.getVariety().getLabel();
        String period = s.getPeriodType() == null ? "" : s.getPeriodType().getLabel();
        return variety + period + (side == Side.SELL ? "售电挂牌 " : "购电挂牌 ") + Nums.qty(qty) + "MWh";
    }

    // ── 日前现货 24 点曲线 ──────────────────────────────────

    /** 返回 true 表示这条曲线已经成功报进场次 */
    private boolean submitSpot(Robot r, TradingAccount acct, Sim sim, TradingSession s,
                               RobotConfig cfg, Random rnd, Tally tally) {
        MemberType mt = memberTypeOf(r, acct);
        // 发电报供给曲线，用电与售电报需求曲线
        Side side = mt == MemberType.GENCO ? Side.SELL : Side.BUY;
        OpsImpl ops = new OpsImpl(r, acct, sim, s, cfg, null, tally);
        double ref = marketPort.referencePrice(sim.getId(), s.instrumentKey());
        if (ref <= 0) {
            return false;
        }
        List<SpotSegment> segments =
                spotCurveBuilder.build(acct, mt, side, s, cfg, ref, rnd, ops::fixPrice);
        try {
            tradePort.submitSpotBid(acct.getId(), s.getId(), side, tradeDate(sim, s),
                    segments, OrderSource.ROBOT);
            tally.spots++;
            tally.lastAction = (side == Side.SELL ? "现货售电曲线" : "现货购电曲线") + " 24 点";
            return true;
        } catch (BizException e) {
            log.debug("机器人 {} 现货申报被拒: {}", r.getName(), e.getMessage());
        } catch (Exception e) {
            log.debug("机器人 {} 现货申报异常: {}", r.getName(), e.toString());
        }
        return false;
    }

    // ── 双边协商邀约 ────────────────────────────────────────

    private boolean proposeNego(Robot r, TradingAccount acct, Sim sim, TradingSession s,
                                RobotConfig cfg, State st, Tally tally) {
        double ref = marketPort.referencePrice(sim.getId(), s.instrumentKey());
        if (ref <= 0) {
            return false;
        }
        List<TradingAccount> peers;
        try {
            peers = accountPort.listBySim(sim.getId());
        } catch (Exception e) {
            log.debug("获取模拟盘账户失败 {}: {}", sim.getId(), e.getMessage());
            return false;
        }
        OpsImpl ops = new OpsImpl(r, acct, sim, s, cfg, null, tally);
        String action = negotiationInitiator.propose(r, acct, memberTypeOf(r, acct), s,
                peers, cfg, st.rnd, ref, ops);
        if (action == null) {
            return false;
        }
        tally.negos++;
        tally.lastAction = action;
        return true;
    }

    private String tradeDate(Sim sim, TradingSession s) {
        if (s.getDeliveryStart() != null && !s.getDeliveryStart().isBlank()) {
            return s.getDeliveryStart();
        }
        if (sim.getBaseDate() != null && !sim.getBaseDate().isBlank()) {
            return sim.getBaseDate();
        }
        return LocalDate.now().toString();
    }

    // ── 工具 ────────────────────────────────────────────────

    private List<TradingSession> openSessions(String simId) {
        try {
            List<TradingSession> all = simPort.listOpenSessions(simId);
            if (all == null) {
                return List.of();
            }
            List<TradingSession> out = new ArrayList<>(all.size());
            for (TradingSession s : all) {
                if (s != null && s.getState() != null && s.getState().acceptsOrders()) {
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("获取可交易场次失败 {}: {}", simId, e.getMessage());
            return List.of();
        }
    }

    private void seedOrderMemory(State st, TradingAccount acct) {
        if (st.seeded) {
            return;
        }
        st.seeded = true;
        try {
            for (Order o : orderRepo.listByAccount(acct.getId())) {
                if (o.getStatus() == OrderStatus.ACTIVE || o.getStatus() == OrderStatus.PARTIAL) {
                    st.myOrders.addLast(o.getId());
                    if (st.myOrders.size() >= ORDER_MEMORY) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("加载历史挂单失败 {}: {}", acct.getId(), e.getMessage());
        }
    }

    private void writeBack(Robot r, TradingAccount acct, Tally tally) {
        if (!tally.any()) {
            return;
        }
        try {
            r.setOrderCount(r.getOrderCount() + tally.orders);
            r.setCancelCount(r.getCancelCount() + tally.cancels);
            if (acct.getStat() != null) {
                r.setTradeCount(acct.getStat().getTradeCount());
            }
            r.setLastActAt(System.currentTimeMillis());
            if (tally.lastAction != null) {
                r.setLastAction(tally.lastAction);
            }
            r.setUpdatedAt(r.getLastActAt());
            robotRepo.save(r.getId(), r);
        } catch (Exception e) {
            log.debug("回写机器人统计失败 {}: {}", r.getId(), e.getMessage());
        }
    }

    private MemberType memberTypeOf(Robot r, TradingAccount acct) {
        if (acct != null && acct.getMemberType() != null) {
            return acct.getMemberType();
        }
        return r.getMemberType() == null ? MemberType.RETAILER : r.getMemberType();
    }

    private <T> T pick(List<T> list, Random rnd) {
        return list.get(rnd.nextInt(list.size()));
    }

    // ── 策略动作实现 ────────────────────────────────────────

    /** 把策略给出的"想报什么"翻译成合规的申报，并统一处理风控拒绝 */
    private class OpsImpl implements RobotStrategyHandler.Ops, NegotiationInitiator.Limits {

        private final Robot robot;
        private final TradingAccount acct;
        private final Sim sim;
        private final TradingSession session;
        private final RobotConfig cfg;
        private final State state;
        private final Tally tally;

        OpsImpl(Robot robot, TradingAccount acct, Sim sim, TradingSession session,
                RobotConfig cfg, State state, Tally tally) {
            this.robot = robot;
            this.acct = acct;
            this.sim = sim;
            this.session = session;
            this.cfg = cfg;
            this.state = state;
            this.tally = tally;
        }

        @Override
        public boolean place(Side side, double price, double quantity, TimeInForce tif) {
            double p = fixPrice(price);
            double q = capByRisk(side, p, fixQty(quantity));
            if (p <= 0 || q <= 0) {
                return false;
            }
            try {
                Order o = tradePort.placeOrder(PlaceOrderCmd.builder()
                        .accountId(acct.getId())
                        .sessionId(session.getId())
                        .side(side)
                        .price(p)
                        .quantity(q)
                        .timeInForce(tif == null ? TimeInForce.GFD : tif)
                        .source(OrderSource.ROBOT)
                        .build());
                if (o == null || o.getId() == null) {
                    return false;
                }
                remember(o);
                tally.orders++;
                return true;
            } catch (BizException e) {
                log.debug("机器人 {} 申报被拒 {} {}@{}: {}", robot.getName(), side, q, p, e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("机器人 {} 申报异常: {}", robot.getName(), e.toString());
                return false;
            }
        }

        @Override
        public double fixPrice(double raw) {
            if (raw <= 0 || Double.isNaN(raw)) {
                return 0;
            }
            double floor = session.getPriceFloor() > 0 ? session.getPriceFloor() : 0.01;
            double cap = session.getPriceCap() > 0 ? session.getPriceCap() : raw * 10;
            SimConfig sc = sim.getCfg();
            double limitPct = sc == null ? 0.15 : sc.getPriceLimitPct();
            double tick = session.getTickSize() > 0 ? session.getTickSize() : 0.01;
            if (session.getPrevClose() > 0 && limitPct > 0) {
                // 风控是拿 Nums.price 四舍五入后的涨跌停价比较的，贴边报价可能因为
                // 舍入方向不同被打回，所以主动往里收一个最小变动价位
                floor = Math.max(floor, Nums.price(session.getPrevClose() * (1 - limitPct)) + tick);
                cap = Math.min(cap, Nums.price(session.getPrevClose() * (1 + limitPct)) - tick);
            }
            if (cap < floor) {
                cap = floor;
            }
            double p = Nums.alignTick(Nums.clamp(raw, floor, cap), tick);
            // 对齐时的四舍五入可能刚好越过边界，往回收一个 tick
            if (p > cap) {
                p = Nums.price(Math.floor(cap / tick) * tick);
            }
            if (p < floor) {
                p = Nums.price(Math.ceil(floor / tick) * tick);
            }
            return Nums.price(p);
        }

        @Override
        public double fixQty(double raw) {
            if (raw <= 0 || Double.isNaN(raw)) {
                return 0;
            }
            double lot = session.getLotSize() > 0 ? session.getLotSize() : 1;
            double min = session.getMinQty() > 0 ? session.getMinQty() : lot;
            double max = session.getMaxQty() > 0 ? session.getMaxQty() : Double.MAX_VALUE;
            AccountParams p = acct.getParams();
            if (p != null && p.getMaxSingleOrderMwh() != null && p.getMaxSingleOrderMwh() > 0) {
                max = Math.min(max, p.getMaxSingleOrderMwh());
            }
            if (cfg.getMaxOrderMwh() > 0) {
                max = Math.min(max, cfg.getMaxOrderMwh() * 3);
            }
            double q = Math.floor(Math.min(raw, max) / lot) * lot;
            if (q < min) {
                double up = Math.ceil(min / lot) * lot;
                if (up > max) {
                    return 0;
                }
                q = up;
            }
            return Nums.qty(q);
        }

        @Override
        public List<Double> closes(int n) {
            try {
                return klineService.recentCloses(sim.getId(), session.instrumentKey(), "1m", Math.max(n, 2));
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public double refOf(Variety variety) {
            try {
                return marketPort.referencePrice(sim.getId(),
                        TradingSession.instrumentKey(variety, session.getPeriodType()));
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public double inventory() {
            return acct.boughtMwh() - acct.soldMwh();
        }

        @Override
        public void mark(String action) {
            tally.lastAction = action;
        }

        /**
         * 这个方向上真正能吃下的最大电量：受可用资金、可售电量与持仓限额三重约束。
         * 不套场次的最小申报量——摘牌走的是挂牌方自己的 minTakeQty，两者规则不同。
         */
        @Override
        public double capacity(Side side, double price) {
            if (price <= 0) {
                return 0;
            }
            double available = acct.getFund() == null ? 0 : acct.getFund().getAvailable();
            if (available <= 0) {
                return 0;
            }
            SimConfig sc = sim.getCfg();
            AccountParams p = acct.getParams();
            double q;
            if (side == Side.BUY) {
                double feeRate = sc == null ? 0.0005 : sc.getFeeRateBuy();
                q = available * RESOURCE_USAGE / (price * (1 + feeRate));
            } else {
                double marginRatio = sc == null ? 0.20 : sc.getMarginRatio();
                q = marginRatio > 0 ? available * RESOURCE_USAGE / (price * marginRatio) : Double.MAX_VALUE;
                if (sc == null || !sc.isAllowNakedShort()) {
                    // 与 RiskService.checkSellableEnergy 同口径：
                    // 可售 = 年度可发电量 + 已买入 - 已卖出。发电以外的主体只能转售买来的电量。
                    double base = p == null || p.getAnnualEnergyMwh() == null ? 0 : p.getAnnualEnergyMwh();
                    q = Math.min(q, base + acct.boughtMwh() - acct.soldMwh());
                }
            }
            if (p != null && p.getMaxPositionMwh() != null && p.getMaxPositionMwh() > 0) {
                // 敞口做大的方向才需要留额度，减仓方向风控永远放行
                double net = inventory();
                double room = side == Side.BUY ? p.getMaxPositionMwh() - net : p.getMaxPositionMwh() + net;
                q = Math.min(q, room);
            }
            return Math.max(q, 0);
        }

        /** 申报单口径：先按风控能力封顶，再套场次的最小单位与限额 */
        double capByRisk(Side side, double price, double quantity) {
            if (price <= 0 || quantity <= 0) {
                return 0;
            }
            return fixQty(Math.min(quantity, capacity(side, price)));
        }

        private void remember(Order o) {
            if (state == null) {
                return;
            }
            if (o.getStatus() != OrderStatus.ACTIVE && o.getStatus() != OrderStatus.PARTIAL) {
                return;
            }
            state.myOrders.addLast(o.getId());
            while (state.myOrders.size() > ORDER_MEMORY) {
                state.myOrders.pollFirst();
            }
        }
    }
}
