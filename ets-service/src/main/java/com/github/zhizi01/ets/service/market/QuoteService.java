package com.github.zhizi01.ets.service.market;

import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.ListingStatus;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.Quote;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.ListingRepo;
import com.github.zhizi01.ets.repo.QuoteRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标的行情快照。一个 instrumentKey（品种_时段）可能同时挂着若干场次，
 * Quote 是它们的聚合视图：成交统计全量累加，买一卖一取滚动撮合场次里最优的一档。
 * <p>
 * 逐笔成交只改内存，落盘与推送由 {@link MarketServiceImpl} 的节流线程统一做。
 */
@Slf4j
@Service
public class QuoteService {

    /** 场次索引的刷新间隔，场次增删属于低频操作，不必每次成交都重扫 */
    private static final long SESSION_INDEX_TTL_MS = 5_000;

    private final QuoteRepo quoteRepo;
    private final ListingRepo listingRepo;
    private final DepthService depthService;
    private final SimPort simPort;

    private final Map<String, Quote> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dirty = new ConcurrentHashMap<>();

    /** simId -> (instrumentKey -> 该标的下的场次) */
    private final Map<String, Map<String, List<TradingSession>>> sessionIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionIndexAt = new ConcurrentHashMap<>();

    public QuoteService(QuoteRepo quoteRepo, ListingRepo listingRepo,
                        DepthService depthService, @Lazy SimPort simPort) {
        this.quoteRepo = quoteRepo;
        this.listingRepo = listingRepo;
        this.depthService = depthService;
        this.simPort = simPort;
    }

    // ── 读取 ────────────────────────────────────────────────

    public Quote get(String simId, String instrumentKey) {
        if (simId == null || instrumentKey == null) {
            return null;
        }
        return load(simId, instrumentKey);
    }

    /** 该盘全部标的，内存中的最新值优先于 Redis 快照 */
    public List<Quote> listBySim(String simId) {
        Map<String, Quote> merged = new LinkedHashMap<>();
        try {
            for (Quote q : quoteRepo.listBySim(simId)) {
                if (q != null && q.getInstrumentKey() != null) {
                    merged.put(q.getInstrumentKey(), q);
                }
            }
        } catch (Exception e) {
            log.debug("读取行情列表失败 {}: {}", simId, e.getMessage());
        }
        String prefix = simId + "|";
        cache.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                merged.put(v.getInstrumentKey(), v);
            }
        });
        return new ArrayList<>(merged.values());
    }

    // ── 写入 ────────────────────────────────────────────────

    /** 建场次时铺初值：前收 = 今开 = 最新 = 基准价，图表与涨跌幅从第一秒起就有参照 */
    public Quote initInstrument(String simId, String instrumentKey, double basePrice) {
        Quote q = load(simId, instrumentKey);
        synchronized (q) {
            double p = Nums.price(basePrice > 0 ? basePrice : q.getLast());
            if (p <= 0) {
                return q;
            }
            if (q.getPrevClose() <= 0) {
                q.setPrevClose(p);
            }
            if (q.getLast() <= 0) {
                q.setOpen(p);
                q.setHigh(p);
                q.setLow(p);
                q.setLast(p);
                q.setAvg(p);
            }
            recalcChange(q);
        }
        save(q);
        return q;
    }

    /**
     * 成交入账。
     * BILATERAL 是场外私下达成的价格，只计入成交量与成交额，不改动公开最新价与高低开。
     */
    public void applyTrade(Trade t) {
        if (t == null || t.getVariety() == null) {
            return;
        }
        String ik = t.instrumentKey();
        Quote q = load(t.getSimId(), ik);
        boolean publicPrice = t.getTradeMode() != TradeMode.BILATERAL;
        synchronized (q) {
            q.setVolume(Nums.qty(q.getVolume() + t.getQuantity()));
            q.setAmount(Nums.amount(q.getAmount() + t.getAmount()));
            q.setTradeCount(q.getTradeCount() + 1);
            q.setAvg(Nums.price(Nums.div(q.getAmount(), q.getVolume())));
            if (publicPrice) {
                double p = Nums.price(t.getPrice());
                if (q.getOpen() <= 0) {
                    q.setOpen(p);
                }
                q.setHigh(q.getHigh() <= 0 ? p : Math.max(q.getHigh(), p));
                q.setLow(q.getLow() <= 0 ? p : Math.min(q.getLow(), p));
                q.setLast(p);
                recalcChange(q);
            }
        }
        markDirty(t.getSimId(), ik);
    }

    public void markDirty(String simId, String instrumentKey) {
        if (simId != null && instrumentKey != null) {
            dirty.put(key(simId, instrumentKey), Boolean.TRUE);
        }
    }

    /** 某场次盘口变了，连带把它所属标的的快照标脏 */
    public void markDirtyBySession(TradingSession s) {
        if (s != null && s.getVariety() != null) {
            markDirty(s.getSimId(), s.instrumentKey());
        }
    }

    /** 落盘并返回本轮有变化的快照，交由调用方广播 */
    public List<Quote> flushDirty() {
        if (dirty.isEmpty()) {
            return List.of();
        }
        List<Quote> out = new ArrayList<>(dirty.size());
        for (String ck : new ArrayList<>(dirty.keySet())) {
            if (dirty.remove(ck) == null) {
                continue;
            }
            Quote q = cache.get(ck);
            if (q == null) {
                continue;
            }
            try {
                refreshBook(q);
                save(q);
                out.add(q);
            } catch (Exception e) {
                log.debug("行情快照刷新失败 {}: {}", ck, e.getMessage());
            }
        }
        return out;
    }

    /** 回填买一卖一、活跃场次数与挂牌数 */
    public void refreshBook(Quote q) {
        List<TradingSession> sessions = sessionsOf(q.getSimId(), q.getInstrumentKey());
        double bid1 = 0;
        double bid1Qty = 0;
        double ask1 = 0;
        double ask1Qty = 0;
        int active = 0;
        int listings = 0;
        for (TradingSession s : sessions) {
            TSessionState st = s.getState();
            if (st != null && st.acceptsOrders()) {
                active++;
            }
            if (st == TSessionState.OPEN && s.getTradeMode() == TradeMode.ROLLING_MATCH) {
                double[] best = depthService.bestWithQty(s.getId());
                if (best[0] > 0 && (bid1 == 0 || best[0] > bid1)) {
                    bid1 = best[0];
                    bid1Qty = best[1];
                }
                if (best[2] > 0 && (ask1 == 0 || best[2] < ask1)) {
                    ask1 = best[2];
                    ask1Qty = best[3];
                }
            }
            if (s.getTradeMode() == TradeMode.LISTING && st != null && st.acceptsOrders()) {
                listings += countOpenListings(s.getId());
            }
        }
        synchronized (q) {
            q.setBid1(bid1);
            q.setBid1Qty(bid1Qty);
            q.setAsk1(ask1);
            q.setAsk1Qty(ask1Qty);
            q.setActiveSessions(active);
            q.setActiveListings(listings);
        }
    }

    /** 场次前收，供参考价兜底 */
    public double prevCloseOf(String simId, String instrumentKey) {
        for (TradingSession s : sessionsOf(simId, instrumentKey)) {
            if (s.getPrevClose() > 0) {
                return s.getPrevClose();
            }
            if (s.getLastPrice() > 0) {
                return s.getLastPrice();
            }
        }
        return 0;
    }

    public List<TradingSession> sessionsOf(String simId, String instrumentKey) {
        Map<String, List<TradingSession>> idx = sessionIndex(simId);
        return idx.getOrDefault(instrumentKey, List.of());
    }

    public List<TradingSession> allSessions(String simId) {
        List<TradingSession> out = new ArrayList<>();
        sessionIndex(simId).values().forEach(out::addAll);
        return out;
    }

    /** 重置演练后清空内存态 */
    public void evict(String simId) {
        String prefix = simId + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        dirty.keySet().removeIf(k -> k.startsWith(prefix));
        sessionIndex.remove(simId);
        sessionIndexAt.remove(simId);
    }

    // ── 内部 ────────────────────────────────────────────────

    private Quote load(String simId, String instrumentKey) {
        return cache.computeIfAbsent(key(simId, instrumentKey), k -> {
            Quote q = null;
            try {
                q = quoteRepo.find(simId, instrumentKey);
            } catch (Exception e) {
                log.debug("读取行情失败 {} {}: {}", simId, instrumentKey, e.getMessage());
            }
            return q != null ? q : blank(simId, instrumentKey);
        });
    }

    private Quote blank(String simId, String instrumentKey) {
        Quote q = new Quote();
        q.setSimId(simId);
        q.setInstrumentKey(instrumentKey);
        fillLabels(q);
        return q;
    }

    private void fillLabels(Quote q) {
        String ik = q.getInstrumentKey();
        int cut = ik == null ? -1 : ik.lastIndexOf('_');
        if (cut <= 0) {
            return;
        }
        try {
            q.setVarietyLabel(Variety.valueOf(ik.substring(0, cut)).getLabel());
        } catch (IllegalArgumentException ignored) {
            // instrumentKey 由场次生成，理论上不会出现未知品种
        }
        try {
            q.setPeriodLabel(PeriodType.valueOf(ik.substring(cut + 1)).getLabel());
        } catch (IllegalArgumentException ignored) {
            // 同上
        }
    }

    private void recalcChange(Quote q) {
        if (q.getPrevClose() > 0 && q.getLast() > 0) {
            q.setChange(Nums.price(q.getLast() - q.getPrevClose()));
            q.setChangePct(Nums.round(Nums.div(q.getChange(), q.getPrevClose()), 6));
        }
    }

    private void save(Quote q) {
        try {
            quoteRepo.save(q);
        } catch (Exception e) {
            log.debug("行情落盘失败 {}: {}", q.getInstrumentKey(), e.getMessage());
        }
    }

    private int countOpenListings(String sessionId) {
        try {
            long now = System.currentTimeMillis();
            int n = 0;
            for (Listing l : listingRepo.recentBySession(sessionId, 200)) {
                ListingStatus st = l.getStatus();
                if (st != null && st.isTakeable() && !l.expired(now) && l.getRemainQty() > 1e-9) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String, List<TradingSession>> sessionIndex(String simId) {
        long now = System.currentTimeMillis();
        Long at = sessionIndexAt.get(simId);
        Map<String, List<TradingSession>> idx = sessionIndex.get(simId);
        if (idx != null && at != null && now - at < SESSION_INDEX_TTL_MS) {
            return idx;
        }
        Map<String, List<TradingSession>> built = new LinkedHashMap<>();
        try {
            for (TradingSession s : simPort.listSessions(simId)) {
                if (s != null && s.getVariety() != null) {
                    built.computeIfAbsent(s.instrumentKey(), k -> new ArrayList<>()).add(s);
                }
            }
            sessionIndex.put(simId, built);
            sessionIndexAt.put(simId, now);
            return built;
        } catch (Exception e) {
            log.debug("刷新场次索引失败 {}: {}", simId, e.getMessage());
            return idx == null ? Map.of() : idx;
        }
    }

    private static String key(String simId, String instrumentKey) {
        return simId + "|" + instrumentKey;
    }
}
