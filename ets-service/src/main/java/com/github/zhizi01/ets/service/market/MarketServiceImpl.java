package com.github.zhizi01.ets.service.market;

import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.dto.MarketTradeVO;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.model.Quote;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.ws.WsTopics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行情门面（MarketPort 实现）。
 * <p>
 * 推送策略：逐笔成交 trade.{sessionId} 实时推，其余（quote / depth / kline）
 * 只标脏，由 marketScheduler 按 ets.market.quote-push-interval-ms 批量合并后推送，
 * 否则一个活跃场次每秒几十笔成交会把前端刷爆。
 */
@Slf4j
@Service
public class MarketServiceImpl implements MarketPort {

    /** 每隔这么多轮做一次全量刷新，保证没有成交时盘口与挂牌数也会更新 */
    private static final int FULL_REFRESH_EVERY = 5;

    private final QuoteService quoteService;
    private final KlineService klineService;
    private final DepthService depthService;
    private final FundamentalService fundamentalService;
    private final WsPort wsPort;
    private final SimPort simPort;
    private final EtsProperties props;
    private final ScheduledExecutorService marketScheduler;

    /** 盘口有变动、等待下一轮推送的场次 */
    private final Map<String, Boolean> dirtySessions = new ConcurrentHashMap<>();
    private final AtomicLong round = new AtomicLong();
    private ScheduledFuture<?> task;

    public MarketServiceImpl(QuoteService quoteService,
                             KlineService klineService,
                             DepthService depthService,
                             FundamentalService fundamentalService,
                             WsPort wsPort,
                             @Lazy SimPort simPort,
                             EtsProperties props,
                             @Qualifier("marketScheduler") ScheduledExecutorService marketScheduler) {
        this.quoteService = quoteService;
        this.klineService = klineService;
        this.depthService = depthService;
        this.fundamentalService = fundamentalService;
        this.wsPort = wsPort;
        this.simPort = simPort;
        this.props = props;
        this.marketScheduler = marketScheduler;
    }

    @PostConstruct
    public void start() {
        long interval = Math.max(props.getMarket().getQuotePushIntervalMs(), 200);
        task = marketScheduler.scheduleWithFixedDelay(this::pushTick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("行情推送线程已启动，节流间隔 {} ms", interval);
    }

    @PreDestroy
    public void stop() {
        if (task != null) {
            task.cancel(false);
        }
    }

    // ── MarketPort ──────────────────────────────────────────

    @Override
    public void onTrade(Trade t) {
        if (t == null) {
            return;
        }
        try {
            boolean publicPrice = t.getTradeMode() != TradeMode.BILATERAL;
            applyToSession(t, publicPrice);
            quoteService.applyTrade(t);
            if (publicPrice) {
                klineService.onTrade(t.getSimId(), t.instrumentKey(), t.getPrice(), t.getQuantity(),
                        t.getTradedAt() > 0 ? t.getTradedAt() : System.currentTimeMillis());
            }
            // 逐笔成交是行情最有生命力的部分，不做节流
            wsPort.push(WsTopics.trade(t.getSessionId()), MarketTradeVO.of(t));
            onBookChanged(t.getSessionId());
        } catch (Exception e) {
            log.warn("成交行情处理失败 trade={}: {}", t.getId(), e.getMessage());
        }
    }

    @Override
    public void onBookChanged(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            dirtySessions.put(sessionId, Boolean.TRUE);
        }
    }

    @Override
    public Quote quote(String simId, String instrumentKey) {
        return quoteService.get(simId, instrumentKey);
    }

    /**
     * 参考价：最新成交价 -> 场次前收 -> 基本面基准价 × 时段比价。
     * 这是机器人定价的锚，任何情况下都不能返回 0。
     */
    @Override
    public double referencePrice(String simId, String instrumentKey) {
        if (simId == null || instrumentKey == null) {
            return 0;
        }
        Quote q = quoteService.get(simId, instrumentKey);
        if (q != null) {
            if (q.getLast() > 0) {
                return q.getLast();
            }
            if (q.getPrevClose() > 0) {
                return q.getPrevClose();
            }
        }
        double prevClose = quoteService.prevCloseOf(simId, instrumentKey);
        if (prevClose > 0) {
            return Nums.price(prevClose);
        }
        double bench = fundamentalService.benchmarkPrice(simId);
        double p = Nums.price(bench * periodFactor(instrumentKey));
        return p > 0 ? p : 380;
    }

    @Override
    public double midPrice(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        double[] best = depthService.best(sessionId);
        if (best[0] > 0 && best[1] > 0) {
            return Nums.price((best[0] + best[1]) / 2);
        }
        if (best[0] > 0) {
            return best[0];
        }
        if (best[1] > 0) {
            return best[1];
        }
        TradingSession s = safeSession(sessionId);
        if (s == null) {
            return 0;
        }
        if (s.getLastPrice() > 0) {
            return s.getLastPrice();
        }
        return referencePrice(s.getSimId(), s.instrumentKey());
    }

    @Override
    public Depth depth(String sessionId, int level) {
        return depthService.depth(sessionId, level);
    }

    @Override
    public void initInstrument(String simId, String instrumentKey, double basePrice) {
        if (simId == null || instrumentKey == null) {
            return;
        }
        double base = basePrice > 0 ? basePrice
                : Nums.price(fundamentalService.benchmarkPrice(simId) * periodFactor(instrumentKey));
        Quote q = quoteService.initInstrument(simId, instrumentKey, base);
        klineService.seed(simId, instrumentKey, base, System.currentTimeMillis());
        if (q != null) {
            wsPort.push(WsTopics.quote(simId, instrumentKey), q);
        }
        log.debug("初始化标的行情 {} {} 基准价 {}", simId, instrumentKey, base);
    }

    // ── 对外补充能力（非端口方法，供本模块与机器人使用） ────

    public List<Quote> quotes(String simId) {
        return quoteService.listBySim(simId);
    }

    public KlineService klines() {
        return klineService;
    }

    /** 场次闭市时把收盘价沉淀为下一场的前收 */
    public void rollPrevClose(String simId, String instrumentKey) {
        Quote q = quoteService.get(simId, instrumentKey);
        if (q == null || q.getLast() <= 0) {
            return;
        }
        q.setPrevClose(q.getLast());
        q.setChange(0);
        q.setChangePct(0);
        quoteService.markDirty(simId, instrumentKey);
    }

    // ── 节流推送 ────────────────────────────────────────────

    private void pushTick() {
        try {
            long n = round.incrementAndGet();
            if (n % FULL_REFRESH_EVERY == 0) {
                markRunningSims();
            }
            pushDepths();
            pushQuotes();
            pushKlines();
        } catch (Exception e) {
            log.warn("行情推送轮次异常: {}", e.getMessage());
        }
    }

    private void pushDepths() {
        if (dirtySessions.isEmpty()) {
            return;
        }
        for (String sessionId : new ArrayList<>(dirtySessions.keySet())) {
            if (dirtySessions.remove(sessionId) == null) {
                continue;
            }
            try {
                TradingSession s = safeSession(sessionId);
                if (s != null) {
                    quoteService.markDirtyBySession(s);
                }
                wsPort.push(WsTopics.depth(sessionId), depthService.depth(sessionId, 5));
            } catch (Exception e) {
                log.debug("盘口推送失败 {}: {}", sessionId, e.getMessage());
            }
        }
    }

    private void pushQuotes() {
        for (Quote q : quoteService.flushDirty()) {
            wsPort.push(WsTopics.quote(q.getSimId(), q.getInstrumentKey()), q);
        }
    }

    private void pushKlines() {
        for (KlineService.Ref ref : klineService.flushDirty()) {
            wsPort.push(WsTopics.kline(ref.getSimId(), ref.getInstrumentKey(), ref.getInterval()), ref.getBar());
        }
    }

    /** 没有成交时也让运行中的盘刷新一次买一卖一与挂牌数 */
    private void markRunningSims() {
        try {
            for (Sim sim : simPort.listRunning()) {
                for (TradingSession s : quoteService.allSessions(sim.getId())) {
                    if (s.getState() != null && s.getState().acceptsOrders()) {
                        quoteService.markDirtyBySession(s);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("全量行情刷新失败: {}", e.getMessage());
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    /** 场次维度的价量统计。双边协商只累加量与额，不动公开价格。 */
    private void applyToSession(Trade t, boolean publicPrice) {
        TradingSession s = safeSession(t.getSessionId());
        if (s == null) {
            return;
        }
        s.setTotalVolume(Nums.qty(s.getTotalVolume() + t.getQuantity()));
        s.setTotalAmount(Nums.amount(s.getTotalAmount() + t.getAmount()));
        if (publicPrice) {
            double p = Nums.price(t.getPrice());
            if (s.getOpenPrice() <= 0) {
                s.setOpenPrice(p);
            }
            s.setHighPrice(s.getHighPrice() <= 0 ? p : Math.max(s.getHighPrice(), p));
            s.setLowPrice(s.getLowPrice() <= 0 ? p : Math.min(s.getLowPrice(), p));
            s.setLastPrice(p);
        }
        s.setAvgPrice(Nums.price(Nums.div(s.getTotalAmount(), s.getTotalVolume())));
        s.setUpdatedAt(System.currentTimeMillis());
        try {
            simPort.saveSession(s);
        } catch (Exception e) {
            log.debug("场次行情落盘失败 {}: {}", s.getId(), e.getMessage());
        }
    }

    private double periodFactor(String instrumentKey) {
        int cut = instrumentKey.lastIndexOf('_');
        if (cut <= 0) {
            return 1.0;
        }
        try {
            return PeriodType.valueOf(instrumentKey.substring(cut + 1)).getPriceFactor();
        } catch (IllegalArgumentException e) {
            return 1.0;
        }
    }

    private TradingSession safeSession(String sessionId) {
        try {
            return simPort.findSession(sessionId);
        } catch (Exception e) {
            return null;
        }
    }
}
