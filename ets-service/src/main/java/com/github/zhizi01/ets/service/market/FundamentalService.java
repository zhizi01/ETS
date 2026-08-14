package com.github.zhizi01.ets.service.market;

import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.dto.FundamentalUpdateReq;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.Fundamental;
import com.github.zhizi01.ets.domain.model.MarketEvent;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.repo.MarketEventRepo;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.ws.WsTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 市场基本面：燃料、负荷、新能源出力的缓慢随机游走，叠加管理员注入的市场事件，
 * 推算出全盘的基准电价。它既是行情自然波动的来源，也是机器人报价的锚。
 */
@Slf4j
@Service
public class FundamentalService {

    /** 每次扰动的幅度（相对值），够小才像"缓慢游走"而不是抽风 */
    private static final double NOISE = 0.005;
    /** 向 1.0 回归的速度 */
    private static final double REVERT = 0.06;
    /** 基准价向目标价靠拢的速度，避免事件生效时价格瞬间跳变 */
    private static final double SMOOTH = 0.35;
    private static final double DEFAULT_BENCHMARK = 380;

    private final SimRepo simRepo;
    private final MarketEventRepo eventRepo;
    private final SimPort simPort;
    private final WsPort wsPort;

    /**
     * 管理员直接指定基准价时记下推算锚点，否则下一轮漂移会立刻把手工值抹掉。
     * 只存内存：重启后回落到模拟盘配置的 benchmarkPrice，属于可接受的退化。
     */
    private final Map<String, Double> baseOverride = new ConcurrentHashMap<>();

    public FundamentalService(SimRepo simRepo, MarketEventRepo eventRepo,
                              @Lazy SimPort simPort, WsPort wsPort) {
        this.simRepo = simRepo;
        this.eventRepo = eventRepo;
        this.simPort = simPort;
        this.wsPort = wsPort;
    }

    // ── 读取 ────────────────────────────────────────────────

    /** 取该盘基本面，不存在则按 sim.cfg.benchmarkPrice 初始化并落盘 */
    public Fundamental get(String simId) {
        if (simId == null) {
            return null;
        }
        Fundamental f = null;
        try {
            f = simRepo.findFundamental(simId);
        } catch (Exception e) {
            log.debug("读取基本面失败 {}: {}", simId, e.getMessage());
        }
        if (f == null) {
            f = init(simId);
        }
        return f;
    }

    public double benchmarkPrice(String simId) {
        Fundamental f = get(simId);
        double p = f == null ? 0 : f.getBenchmarkPrice();
        return p > 0 ? p : baseOf(simId);
    }

    private Fundamental init(String simId) {
        Fundamental f = new Fundamental();
        f.setSimId(simId);
        f.setBenchmarkPrice(baseOf(simId));
        f.setUpdatedAt(System.currentTimeMillis());
        save(f);
        return f;
    }

    public void save(Fundamental f) {
        try {
            f.setUpdatedAt(System.currentTimeMillis());
            simRepo.saveFundamental(f);
        } catch (Exception e) {
            log.warn("基本面落盘失败 {}: {}", f.getSimId(), e.getMessage());
        }
    }

    // ── 自然漂移 ────────────────────────────────────────────

    /** 只驱动 RUNNING 的盘；停牌与结束的盘冻结在最后一次状态 */
    @Scheduled(fixedDelay = 5000, initialDelay = 8000)
    public void drift() {
        List<Sim> sims;
        try {
            sims = simPort.listRunning();
        } catch (Exception e) {
            log.debug("基本面漂移取盘列表失败: {}", e.getMessage());
            return;
        }
        for (Sim sim : sims) {
            if (sim == null || sim.getState() != SimState.RUNNING) {
                continue;
            }
            try {
                Fundamental f = get(sim.getId());
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                f.setCoalPriceIdx(wander(f.getCoalPriceIdx(), 1.0, 0.75, 1.35, rnd));
                f.setGasPriceIdx(wander(f.getGasPriceIdx(), 1.0, 0.70, 1.45, rnd));
                f.setLoadFactor(wander(f.getLoadFactor(), 1.0, 0.80, 1.25, rnd));
                f.setWindFactor(wander(f.getWindFactor(), 1.0, 0.45, 1.65, rnd));
                f.setSolarFactor(wander(f.getSolarFactor(), 1.0, 0.45, 1.65, rnd));
                f.setOutageRatio(wander(f.getOutageRatio(), 0.03, 0.004, 0.14, rnd));
                recalcPrice(sim.getId(), f, true);
                save(f);
                wsPort.push(WsTopics.sim(sim.getId()), "fundamental", f);
            } catch (Exception e) {
                log.debug("基本面漂移失败 {}: {}", sim.getId(), e.getMessage());
            }
        }
    }

    /** 事件增删后立即重算一次，让机器人下一 tick 就感知到 */
    public Fundamental recompute(String simId) {
        Fundamental f = get(simId);
        if (f == null) {
            return null;
        }
        recalcPrice(simId, f, false);
        save(f);
        wsPort.push(WsTopics.sim(simId), "fundamental", f);
        return f;
    }

    /** 管理员手工干预基本面 */
    public Fundamental update(String simId, FundamentalUpdateReq req) {
        Fundamental f = get(simId);
        if (f == null || req == null) {
            return f;
        }
        if (req.getCoalPriceIdx() != null) {
            f.setCoalPriceIdx(Nums.clamp(req.getCoalPriceIdx(), 0.3, 3.0));
        }
        if (req.getGasPriceIdx() != null) {
            f.setGasPriceIdx(Nums.clamp(req.getGasPriceIdx(), 0.3, 3.0));
        }
        if (req.getLoadFactor() != null) {
            f.setLoadFactor(Nums.clamp(req.getLoadFactor(), 0.3, 3.0));
        }
        if (req.getWindFactor() != null) {
            f.setWindFactor(Nums.clamp(req.getWindFactor(), 0.1, 3.0));
        }
        if (req.getSolarFactor() != null) {
            f.setSolarFactor(Nums.clamp(req.getSolarFactor(), 0.1, 3.0));
        }
        if (req.getOutageRatio() != null) {
            f.setOutageRatio(Nums.clamp(req.getOutageRatio(), 0, 0.5));
        }
        if (req.getBenchmarkPrice() != null && req.getBenchmarkPrice() > 0) {
            // 反解出锚点，使后续漂移围绕管理员设定的价位展开
            double mult = multiplier(simId, f);
            baseOverride.put(simId, Nums.price(Nums.div(req.getBenchmarkPrice(), mult <= 0 ? 1 : mult)));
            f.setBenchmarkPrice(Nums.price(req.getBenchmarkPrice()));
        } else {
            recalcPrice(simId, f, false);
        }
        save(f);
        wsPort.push(WsTopics.sim(simId), "fundamental", f);
        return f;
    }

    /** 重置演练时清掉手工锚点 */
    public void reset(String simId) {
        baseOverride.remove(simId);
    }

    // ── 定价 ────────────────────────────────────────────────

    private void recalcPrice(String simId, Fundamental f, boolean smooth) {
        double base = baseOf(simId);
        double mult = multiplier(simId, f);
        double target = Nums.clamp(base * mult, base * 0.45, base * 1.75);
        double cur = f.getBenchmarkPrice();
        double next = (smooth && cur > 0) ? cur + (target - cur) * SMOOTH : target;
        f.setBenchmarkPrice(Nums.price(next));
    }

    /** 各基本面因子对基准电价的合成弹性 */
    private double multiplier(String simId, Fundamental f) {
        double fuel = f.getCoalPriceIdx() * 0.65 + f.getGasPriceIdx() * 0.35;
        double renewable = (f.getWindFactor() + f.getSolarFactor()) / 2.0;
        double mFuel = 1 + (fuel - 1) * 0.55;
        double mLoad = 1 + (f.getLoadFactor() - 1) * 0.85;
        // 新能源多发压低电价，是电力市场最直观的负相关
        double mRenewable = 1 - (renewable - 1) * 0.18;
        double mOutage = 1 + (f.getOutageRatio() - 0.03) * 1.5;
        double impact = eventImpact(simId);
        f.setEventImpact(Nums.round(impact, 4));
        return mFuel * mLoad * mRenewable * mOutage * (1 + impact);
    }

    private double eventImpact(String simId) {
        try {
            long now = System.currentTimeMillis();
            double sum = 0;
            for (MarketEvent e : eventRepo.activeBySim(simId, now)) {
                sum += e.priceImpact();
            }
            return Nums.clamp(sum, -0.6, 0.9);
        } catch (Exception e) {
            return 0;
        }
    }

    private double baseOf(String simId) {
        Double override = baseOverride.get(simId);
        if (override != null && override > 0) {
            return override;
        }
        try {
            Sim sim = simPort.findSim(simId);
            if (sim != null && sim.getCfg() != null && sim.getCfg().getBenchmarkPrice() > 0) {
                return sim.getCfg().getBenchmarkPrice();
            }
        } catch (Exception ignored) {
            // 模拟盘还没建好或 Redis 抖动，退回全局默认值
        }
        return DEFAULT_BENCHMARK;
    }

    /** 均值回复 + 小幅噪声：既有波动又不会跑飞 */
    private double wander(double cur, double mean, double lo, double hi, ThreadLocalRandom rnd) {
        double v = cur <= 0 ? mean : cur;
        v += (mean - v) * REVERT;
        v *= 1 + (rnd.nextDouble() * 2 - 1) * NOISE;
        return Nums.round(Nums.clamp(v, lo, hi), 4);
    }
}
