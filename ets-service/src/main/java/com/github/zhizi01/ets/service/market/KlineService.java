package com.github.zhizi01.ets.service.market;

import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.model.Bar;
import com.github.zhizi01.ets.repo.KlineRepo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多周期 K 线聚合（1m/5m/15m/1h/1d）。
 * <p>
 * 每笔成交都要落 5 个周期，逐笔直写 Redis 代价太高，
 * 所以当前 bar 常驻内存、只标脏，由行情推送线程按节流间隔统一 upsert 并广播。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final KlineRepo klineRepo;
    private final EtsProperties props;

    /** cacheKey -> 当前周期尚未落盘的 bar */
    private final Map<String, Bar> current = new ConcurrentHashMap<>();
    /** cacheKey -> 待落盘标记 */
    private final Map<String, Ref> dirty = new ConcurrentHashMap<>();

    /** 一根待推送的 bar 及其归属 */
    @Getter
    public static class Ref {
        private final String simId;
        private final String instrumentKey;
        private final String interval;
        private final Bar bar;

        Ref(String simId, String instrumentKey, String interval, Bar bar) {
            this.simId = simId;
            this.instrumentKey = instrumentKey;
            this.interval = interval;
            this.bar = bar;
        }
    }

    public List<String> intervals() {
        List<String> list = props.getMarket().intervalList();
        return list.isEmpty() ? List.of("1m", "5m", "15m", "1h", "1d") : list;
    }

    // ── 写入 ────────────────────────────────────────────────

    /** 成交喂线：把一笔成交打进全部周期的当前 bar */
    public void onTrade(String simId, String instrumentKey, double price, double qty, long ts) {
        if (simId == null || instrumentKey == null || price <= 0) {
            return;
        }
        for (String iv : intervals()) {
            try {
                long slot = alignTs(ts, iv);
                String ck = cacheKey(simId, instrumentKey, iv);
                Bar bar = current.compute(ck, (k, old) -> {
                    if (old != null && old.getTs() == slot) {
                        return old;
                    }
                    // 跨周期或首次：先看 Redis 里有没有同一时段的 bar（进程重启后接着补）
                    Bar loaded = safeBarAt(simId, instrumentKey, iv, slot);
                    return loaded != null ? loaded : Bar.init(slot, price);
                });
                synchronized (bar) {
                    bar.apply(price, qty);
                }
                dirty.put(ck, new Ref(simId, instrumentKey, iv, bar));
            } catch (Exception e) {
                log.debug("K 线聚合失败 {} {} {}: {}", simId, instrumentKey, iv, e.getMessage());
            }
        }
    }

    /** 建标的时先铺一根 1m 基准 bar，避免图表一开始完全空白 */
    public void seed(String simId, String instrumentKey, double basePrice, long ts) {
        if (basePrice <= 0) {
            return;
        }
        for (String iv : intervals()) {
            try {
                long slot = alignTs(ts, iv);
                if (klineRepo.barAt(simId, instrumentKey, iv, slot) == null) {
                    klineRepo.upsert(simId, instrumentKey, iv, Bar.init(slot, basePrice));
                }
            } catch (Exception e) {
                log.debug("K 线初始化失败 {} {}: {}", instrumentKey, iv, e.getMessage());
            }
        }
    }

    /** 落盘全部脏 bar，返回本轮变化的 bar 供广播 */
    public List<Ref> flushDirty() {
        if (dirty.isEmpty()) {
            return List.of();
        }
        List<Ref> out = new ArrayList<>(dirty.size());
        for (String ck : new ArrayList<>(dirty.keySet())) {
            Ref ref = dirty.remove(ck);
            if (ref == null) {
                continue;
            }
            try {
                Bar snapshot;
                synchronized (ref.bar) {
                    snapshot = copy(ref.bar);
                }
                klineRepo.upsert(ref.simId, ref.instrumentKey, ref.interval, snapshot);
                out.add(new Ref(ref.simId, ref.instrumentKey, ref.interval, snapshot));
            } catch (Exception e) {
                log.debug("K 线落盘失败 {}: {}", ck, e.getMessage());
            }
        }
        return out;
    }

    // ── 读取 ────────────────────────────────────────────────

    /**
     * 最近 limit 根，时间升序。内存里的当前 bar 会覆盖 Redis 版本，
     * 保证 REST 查询与 WS 推送看到的是同一根。
     */
    public List<Bar> recent(String simId, String instrumentKey, String interval, int limit) {
        List<Bar> bars = klineRepo.recent(simId, instrumentKey, interval, Math.max(limit, 1));
        Bar live = current.get(cacheKey(simId, instrumentKey, interval));
        if (live == null) {
            return bars;
        }
        Bar snapshot;
        synchronized (live) {
            snapshot = copy(live);
        }
        if (!bars.isEmpty() && bars.get(bars.size() - 1).getTs() == snapshot.getTs()) {
            bars.set(bars.size() - 1, snapshot);
        } else if (bars.isEmpty() || bars.get(bars.size() - 1).getTs() < snapshot.getTs()) {
            bars.add(snapshot);
            if (bars.size() > limit) {
                bars = bars.subList(bars.size() - limit, bars.size());
            }
        }
        return bars;
    }

    /** 趋势策略用：最近 n 根收盘价，时间升序 */
    public List<Double> recentCloses(String simId, String instrumentKey, String interval, int n) {
        List<Double> out = new ArrayList<>(n);
        for (Bar b : recent(simId, instrumentKey, interval, n)) {
            if (b != null && b.getClose() > 0) {
                out.add(b.getClose());
            }
        }
        return out;
    }

    public Bar lastBar(String simId, String instrumentKey, String interval) {
        Bar live = current.get(cacheKey(simId, instrumentKey, interval));
        if (live != null) {
            synchronized (live) {
                return copy(live);
            }
        }
        return klineRepo.lastBar(simId, instrumentKey, interval);
    }

    /** 重置演练后清掉内存缓存，避免旧 bar 复活 */
    public void evict(String simId) {
        String prefix = simId + "|";
        current.keySet().removeIf(k -> k.startsWith(prefix));
        dirty.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ── 周期对齐 ────────────────────────────────────────────

    /** bar 的 ts 一律取所在周期的起点 */
    public static long alignTs(long ts, String interval) {
        if (interval == null || interval.isBlank()) {
            return ts - ts % 60_000L;
        }
        char unit = Character.toLowerCase(interval.charAt(interval.length() - 1));
        int n;
        try {
            n = Integer.parseInt(interval.substring(0, interval.length() - 1));
        } catch (NumberFormatException e) {
            n = 1;
        }
        if (n <= 0) {
            n = 1;
        }
        return switch (unit) {
            // 日线按本地自然日切分，不能用毫秒取模（时区偏移会错位）
            case 'd' -> Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate()
                    .atStartOfDay(ZONE).toInstant().toEpochMilli();
            case 'h' -> floor(ts, n * 3_600_000L);
            case 's' -> floor(ts, n * 1_000L);
            default -> floor(ts, n * 60_000L);
        };
    }

    private static long floor(long ts, long span) {
        return span <= 0 ? ts : ts - Math.floorMod(ts, span);
    }

    private Bar safeBarAt(String simId, String instrumentKey, String interval, long slot) {
        try {
            return klineRepo.barAt(simId, instrumentKey, interval, slot);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bar copy(Bar b) {
        Bar c = new Bar();
        c.setTs(b.getTs());
        c.setOpen(b.getOpen());
        c.setHigh(b.getHigh());
        c.setLow(b.getLow());
        c.setClose(b.getClose());
        c.setVolume(b.getVolume());
        c.setAmount(b.getAmount());
        c.setTradeCount(b.getTradeCount());
        return c;
    }

    private static String cacheKey(String simId, String instrumentKey, String interval) {
        return simId + "|" + instrumentKey + "|" + interval;
    }
}
