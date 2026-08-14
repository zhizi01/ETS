package com.github.zhizi01.ets.service.sim;

import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.api.TradePort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.dto.SessCreateReq;
import com.github.zhizi01.ets.domain.dto.SessUpdateReq;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.repo.TradingSessionRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 交易场次的增删改与状态机。
 * <p>状态迁移只在这里发生：管理端手工操作与 {@link SessionClockService} 的自动推进
 * 都收敛到本类的 open / preOpen / suspend / clear / close，
 * 避免两条路径各写一套导致状态不一致。
 */
@Slf4j
@Service
public class TradingSessionService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 重排时间轴时，第一个场次距离开盘留出的缓冲 */
    private static final long REBASE_LEAD_MS = 30_000L;

    private final TradingSessionRepo sessionRepo;
    private final SimRepo simRepo;
    private final IdGen idGen;
    private final TradePort tradePort;
    private final MarketPort marketPort;
    private final WsPort wsPort;
    private final ExecutorService clearingExecutor;

    /** 正在出清的场次，挡住时钟与管理端的重复触发 */
    private final Set<String> clearing = ConcurrentHashMap.newKeySet();

    public TradingSessionService(TradingSessionRepo sessionRepo,
                                 SimRepo simRepo,
                                 IdGen idGen,
                                 @Qualifier("clearingExecutor") ExecutorService clearingExecutor,
                                 @Lazy TradePort tradePort,
                                 @Lazy MarketPort marketPort,
                                 @Lazy WsPort wsPort) {
        this.clearingExecutor = clearingExecutor;
        this.sessionRepo = sessionRepo;
        this.simRepo = simRepo;
        this.idGen = idGen;
        this.tradePort = tradePort;
        this.marketPort = marketPort;
        this.wsPort = wsPort;
    }

    // ══════════════════════════ 查询 ══════════════════════════

    public TradingSession find(String sessionId) {
        return sessionRepo.find(sessionId);
    }

    public TradingSession require(String sessionId) {
        return sessionRepo.require(sessionId, ErrorCode.SESSION_NOT_FOUND);
    }

    public List<TradingSession> listBySim(String simId) {
        return sessionRepo.listBySim(simId);
    }

    public List<TradingSession> listOpen(String simId) {
        List<TradingSession> out = new ArrayList<>();
        for (TradingSession s : sessionRepo.listBySim(simId)) {
            if (s.getState() != null && s.getState().acceptsOrders()) {
                out.add(s);
            }
        }
        return out;
    }

    public void save(TradingSession session) {
        session.setUpdatedAt(System.currentTimeMillis());
        sessionRepo.saveSession(session);
    }

    // ══════════════════════════ 增删改 ══════════════════════════

    public TradingSession create(SessCreateReq req) {
        Sim sim = simRepo.require(req.getSimId(), ErrorCode.SIM_NOT_FOUND);
        SimConfig cfg = cfgOf(sim);
        Variety variety = req.getVariety();
        PeriodType period = req.getPeriodType() == null ? PeriodType.ALL : req.getPeriodType();
        TradeMode mode = req.getTradeMode();

        TradingSession s = new TradingSession();
        s.setId(idGen.next(IdGen.TSESSION));
        s.setSimId(sim.getId());
        s.setVariety(variety);
        s.setPeriodType(period);
        s.setTradeMode(mode);
        s.setState(TSessionState.PENDING);
        s.setName(req.getName() == null || req.getName().isBlank()
                ? defaultName(variety, period, mode) : req.getName().trim());

        // ── 交割区间 ──
        String[] delivery = defaultDelivery(sim, variety);
        s.setDeliveryStart(req.getDeliveryStart() == null ? delivery[0] : req.getDeliveryStart());
        s.setDeliveryEnd(req.getDeliveryEnd() == null ? delivery[1] : req.getDeliveryEnd());

        // ── 时间轴 ──
        long now = System.currentTimeMillis();
        long openAfter = req.getOpenAfterMinutes() == null ? 1 : Math.max(0, req.getOpenAfterMinutes());
        long duration = req.getDurationMinutes() == null ? defaultDurationMinutes(variety, mode)
                : Math.max(1, req.getDurationMinutes());
        long openTime = req.getOpenTime() != null ? req.getOpenTime() : now + openAfter * 60_000L;
        long closeTime = req.getCloseTime() != null ? req.getCloseTime() : openTime + duration * 60_000L;
        if (closeTime <= openTime) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "闭市时间必须晚于开市时间");
        }
        s.setOpenTime(openTime);
        s.setCloseTime(closeTime);
        // 集中竞价与现货必须有申报期，未指定时与开市时刻对齐
        s.setPreOpenTime(mode != null && mode.isCentralized()
                ? (req.getPreOpenTime() != null ? req.getPreOpenTime() : openTime)
                : (req.getPreOpenTime() == null ? 0 : req.getPreOpenTime()));

        // ── 价格与电量参数 ──
        double base = basePriceOf(cfg, variety, period);
        boolean spot = variety == Variety.SPOT_DA;
        s.setPriceFloor(req.getPriceFloor() != null ? Nums.price(req.getPriceFloor())
                : Nums.round(base * (spot ? 0.40 : 0.70), 0));
        s.setPriceCap(req.getPriceCap() != null ? Nums.price(req.getPriceCap())
                : Nums.round(base * (spot ? 1.80 : 1.30), 0));
        if (s.getPriceCap() <= s.getPriceFloor()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "申报价格上限必须大于下限");
        }
        s.setTickSize(req.getTickSize() != null ? req.getTickSize() : (spot ? 0.01 : 0.1));
        s.setLotSize(req.getLotSize() != null ? req.getLotSize() : defaultLot(variety));
        s.setMinQty(req.getMinQty() != null ? req.getMinQty() : defaultMinQty(variety));
        s.setMaxQty(req.getMaxQty() != null ? req.getMaxQty() : defaultMaxQty(variety));
        if (s.getMaxQty() < s.getMinQty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "单笔电量上限必须不小于下限");
        }
        // 给出前收盘，涨跌幅风控从第一笔申报起就有参照
        s.setPrevClose(Nums.price(base));
        s.setCreatedAt(now);
        save(s);

        initInstrument(s, base);
        log.info("创建交易场次 {} {}（{} ~ {}）", s.getId(), s.getName(),
                s.getDeliveryStart(), s.getDeliveryEnd());
        return s;
    }

    public TradingSession update(String sessionId, SessUpdateReq req) {
        TradingSession s = require(sessionId);
        if (s.getState() == TSessionState.CLOSED || s.getState() == TSessionState.SETTLED) {
            throw BizException.of(ErrorCode.SESSION_CLOSED, "场次已闭市，不能再修改");
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            s.setName(req.getName().trim());
        }
        if (req.getDeliveryStart() != null) {
            s.setDeliveryStart(req.getDeliveryStart());
        }
        if (req.getDeliveryEnd() != null) {
            s.setDeliveryEnd(req.getDeliveryEnd());
        }
        if (req.getPreOpenTime() != null) {
            s.setPreOpenTime(req.getPreOpenTime());
        }
        if (req.getOpenTime() != null) {
            s.setOpenTime(req.getOpenTime());
        }
        if (req.getCloseTime() != null) {
            s.setCloseTime(req.getCloseTime());
        }
        if (s.getCloseTime() <= s.getOpenTime()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "闭市时间必须晚于开市时间");
        }
        if (req.getPriceFloor() != null) {
            s.setPriceFloor(Nums.price(req.getPriceFloor()));
        }
        if (req.getPriceCap() != null) {
            s.setPriceCap(Nums.price(req.getPriceCap()));
        }
        if (s.getPriceCap() <= s.getPriceFloor()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "申报价格上限必须大于下限");
        }
        if (req.getMinQty() != null) {
            s.setMinQty(req.getMinQty());
        }
        if (req.getMaxQty() != null) {
            s.setMaxQty(req.getMaxQty());
        }
        if (req.getTickSize() != null) {
            s.setTickSize(req.getTickSize());
        }
        if (req.getLotSize() != null) {
            s.setLotSize(req.getLotSize());
        }
        save(s);
        return s;
    }

    public void delete(String sessionId) {
        TradingSession s = require(sessionId);
        if (s.getState() == TSessionState.OPEN || s.getState() == TSessionState.PRE_OPEN
                || s.getState() == TSessionState.CLEARING) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次正在「" + s.getState().getLabel() + "」，请先闭市再删除");
        }
        sessionRepo.removeSession(s);
        log.info("删除交易场次 {} {}", s.getId(), s.getName());
    }

    /** 清空某模拟盘下的全部场次，用于「重新生成标准场次」与模拟盘重置。 */
    public int deleteAll(String simId) {
        List<TradingSession> list = sessionRepo.listBySim(simId);
        for (TradingSession s : list) {
            sessionRepo.removeSession(s);
        }
        return list.size();
    }

    // ══════════════════════════ 状态机 ══════════════════════════

    public TradingSession preOpen(String sessionId) {
        TradingSession s = require(sessionId);
        if (s.getTradeMode() == null || !s.getTradeMode().isCentralized()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "只有集中竞价与日前现货才有集中申报阶段");
        }
        expect(s, TSessionState.PENDING);
        return transit(s, TSessionState.PRE_OPEN, "pre-open");
    }

    public TradingSession open(String sessionId) {
        TradingSession s = require(sessionId);
        if (s.getTradeMode() != null && s.getTradeMode().isCentralized()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "集中出清类场次不进入连续交易，请使用「集中申报」与「出清」");
        }
        expect(s, TSessionState.PENDING, TSessionState.SUSPENDED);
        return transit(s, TSessionState.OPEN, "open");
    }

    public TradingSession suspend(String sessionId) {
        TradingSession s = require(sessionId);
        expect(s, TSessionState.OPEN);
        return transit(s, TSessionState.SUSPENDED, "suspend");
    }

    /**
     * 集中出清：先切到 CLEARING 让前端能看到出清中的状态，再按交易方式分派给撮合模块。
     * 无论出清成功与否都要闭市，否则场次会永远卡在「出清中」。
     */
    /**
     * 触发统一出清。校验与置位是同步的（调用方立刻能看到 CLEARING，也借此挡住重复触发），
     * 真正跑出清与闭市收尾丢到 clearingExecutor 上异步执行。
     * <p>日前现货一场要跑 24 个小时段、几百笔成交，同步执行会把场次时钟或 HTTP 请求线程
     * 卡住若干分钟，期间所有模拟盘的场次都不再推进，所以这里必须异步。
     */
    public TradingSession clear(String sessionId) {
        TradingSession s = require(sessionId);
        TradeMode mode = s.getTradeMode();
        if (mode == null || !mode.isCentralized()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "只有集中竞价与日前现货支持统一出清");
        }
        expect(s, TSessionState.PRE_OPEN, TSessionState.OPEN, TSessionState.SUSPENDED);
        if (!clearing.add(sessionId)) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL, "该场次正在出清中，请稍候");
        }
        transit(s, TSessionState.CLEARING, "clearing");
        clearingExecutor.submit(() -> runClearing(sessionId, mode));
        return require(sessionId);
    }

    private void runClearing(String sessionId, TradeMode mode) {
        long t0 = System.currentTimeMillis();
        try {
            if (mode == TradeMode.CALL_AUCTION) {
                tradePort.runCallAuction(sessionId);
            } else {
                tradePort.runSpotClearing(sessionId);
            }
        } catch (Exception e) {
            log.error("场次 {} 出清失败", sessionId, e);
        } finally {
            closeQuietly(sessionId);
            clearing.remove(sessionId);
            log.info("场次 {} 出清结束，耗时 {} ms", sessionId, System.currentTimeMillis() - t0);
        }
    }

    /**
     * 进程若在出清途中被重启，场次会永久停在 CLEARING——时钟的状态机不认这个状态，
     * 管理端的 clear 也只接受 PRE_OPEN/OPEN/SUSPENDED，于是它再也出不去。
     * 启动时统一收尾成 CLOSED：此时已成交的部分都已落库，重跑一遍反而会重复成交。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedClearing() {
        int n = 0;
        for (Sim sim : simRepo.listAll()) {
            for (TradingSession s : sessionRepo.listBySim(sim.getId())) {
                if (s.getState() == TSessionState.CLEARING) {
                    log.warn("场次 {} {} 上次出清被中断，强制收尾闭市（已成交部分保留）", s.getId(), s.getName());
                    closeQuietly(s.getId());
                    n++;
                }
            }
        }
        if (n > 0) {
            log.info("启动恢复：{} 个中断的出清场次已收尾", n);
        }
    }

    public TradingSession close(String sessionId) {
        TradingSession s = require(sessionId);
        if (s.getState() == TSessionState.CLOSED || s.getState() == TSessionState.SETTLED) {
            return s;
        }
        try {
            tradePort.closeSession(sessionId);
        } catch (Exception e) {
            log.warn("场次 {} 闭市时撮合模块清理失败：{}", sessionId, e.getMessage());
        }
        s = require(sessionId);
        if (s.getLastPrice() > 0) {
            s.setPrevClose(s.getLastPrice());
        }
        return transit(s, TSessionState.CLOSED, "close");
    }

    private void closeQuietly(String sessionId) {
        try {
            close(sessionId);
        } catch (Exception e) {
            log.warn("场次 {} 自动闭市失败：{}", sessionId, e.getMessage());
        }
    }

    private TradingSession transit(TradingSession s, TSessionState target, String event) {
        s.setState(target);
        save(s);
        push(s, event);
        log.info("场次 {} {} -> {}", s.getId(), s.getName(), target.getLabel());
        return s;
    }

    private void expect(TradingSession s, TSessionState... allowed) {
        for (TSessionState st : allowed) {
            if (s.getState() == st) {
                return;
            }
        }
        throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                "场次当前为「" + (s.getState() == null ? "未知" : s.getState().getLabel()) + "」，不允许该操作");
    }

    // ══════════════════════════ 时间轴重排 ══════════════════════════

    /**
     * 把尚未开始的场次整体平移，使最早的一场落在 anchor 之后不久。
     * 场次是在筹备期建好的，而演练什么时候开盘由管理员决定，
     * 不重排的话开盘时这些场次往往已经全部过期。相对间隔保持不变。
     */
    public int rebaseClock(String simId, long anchor) {
        List<TradingSession> sessions = sessionRepo.listBySim(simId);
        long earliest = Long.MAX_VALUE;
        for (TradingSession s : sessions) {
            if (s.getState() != TSessionState.PENDING) {
                continue;
            }
            long start = s.getPreOpenTime() > 0 ? Math.min(s.getPreOpenTime(), s.getOpenTime()) : s.getOpenTime();
            earliest = Math.min(earliest, start);
        }
        if (earliest == Long.MAX_VALUE) {
            return 0;
        }
        long delta = anchor + REBASE_LEAD_MS - earliest;
        if (delta <= 0) {
            return 0;
        }
        int n = 0;
        for (TradingSession s : sessions) {
            if (s.getState() != TSessionState.PENDING) {
                continue;
            }
            if (s.getPreOpenTime() > 0) {
                s.setPreOpenTime(s.getPreOpenTime() + delta);
            }
            s.setOpenTime(s.getOpenTime() + delta);
            s.setCloseTime(s.getCloseTime() + delta);
            save(s);
            n++;
        }
        log.info("模拟盘 {} 的 {} 个场次时间轴整体后移 {} 秒", simId, n, delta / 1000);
        return n;
    }

    // ══════════════════════════ 缺省值推算 ══════════════════════════

    /** 标的基准价 = 模拟盘基准电价 × 时段比价 × 品种溢价（越临近交割越贵）。 */
    public static double basePriceOf(SimConfig cfg, Variety variety, PeriodType period) {
        double benchmark = cfg == null || cfg.getBenchmarkPrice() <= 0 ? 380 : cfg.getBenchmarkPrice();
        double periodFactor = (period == null ? PeriodType.ALL : period).getPriceFactor();
        double varietyFactor = switch (variety == null ? Variety.MONTHLY : variety) {
            case ANNUAL -> 0.98;
            case MONTHLY -> 1.00;
            case MULTI_DAY -> 1.02;
            case SPOT_DA -> 1.05;
        };
        return Nums.price(benchmark * periodFactor * varietyFactor);
    }

    public static String defaultName(Variety v, PeriodType p, TradeMode m) {
        String variety = v == null ? "" : v.getLabel();
        String period = (p == null ? PeriodType.ALL : p).getLabel();
        String mode = m == null ? "" : m.getLabel();
        return variety + period + "·" + mode;
    }

    /** 交割区间按品种从模拟盘 baseDate 顺推：年度看次年、月度看次月、月内多日看一周、现货看次日。 */
    public static String[] defaultDelivery(Sim sim, Variety variety) {
        LocalDate base = parseDate(sim == null ? null : sim.getBaseDate());
        return switch (variety == null ? Variety.MONTHLY : variety) {
            case ANNUAL -> {
                LocalDate y = base.plusYears(1).withDayOfYear(1);
                yield new String[]{y.format(DATE), y.withDayOfYear(y.lengthOfYear()).format(DATE)};
            }
            case MONTHLY -> {
                LocalDate m = base.plusMonths(1).withDayOfMonth(1);
                yield new String[]{m.format(DATE), m.withDayOfMonth(m.lengthOfMonth()).format(DATE)};
            }
            case MULTI_DAY -> new String[]{base.plusDays(3).format(DATE), base.plusDays(9).format(DATE)};
            case SPOT_DA -> {
                String d = base.plusDays(1).format(DATE);
                yield new String[]{d, d};
            }
        };
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(v.trim(), DATE);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private static long defaultDurationMinutes(Variety v, TradeMode m) {
        if (m != null && m.isCentralized()) {
            return 30;
        }
        return switch (v == null ? Variety.MONTHLY : v) {
            case ANNUAL -> 240;
            case MONTHLY -> 180;
            case MULTI_DAY -> 120;
            case SPOT_DA -> 60;
        };
    }

    private static double defaultLot(Variety v) {
        return switch (v == null ? Variety.MONTHLY : v) {
            case ANNUAL -> 100;
            case MONTHLY -> 50;
            case MULTI_DAY -> 10;
            case SPOT_DA -> 1;
        };
    }

    private static double defaultMinQty(Variety v) {
        return switch (v == null ? Variety.MONTHLY : v) {
            case ANNUAL -> 1000;
            case MONTHLY -> 500;
            case MULTI_DAY -> 100;
            case SPOT_DA -> 10;
        };
    }

    private static double defaultMaxQty(Variety v) {
        return switch (v == null ? Variety.MONTHLY : v) {
            case ANNUAL -> 500_000;
            case MONTHLY -> 200_000;
            case MULTI_DAY -> 50_000;
            case SPOT_DA -> 5_000;
        };
    }

    private static SimConfig cfgOf(Sim sim) {
        return sim.getCfg() == null ? new SimConfig() : sim.getCfg();
    }

    // ══════════════════════════ 对外联动 ══════════════════════════

    private void initInstrument(TradingSession s, double basePrice) {
        try {
            marketPort.initInstrument(s.getSimId(), s.instrumentKey(), basePrice);
        } catch (Exception e) {
            log.warn("初始化标的 {} 行情失败（行情模块可能尚未就绪）：{}", s.instrumentKey(), e.getMessage());
        }
    }

    void push(TradingSession s, String event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "session." + event);
            payload.put("sessionId", s.getId());
            payload.put("sessionName", s.getName());
            payload.put("state", s.getState() == null ? null : s.getState().name());
            payload.put("stateLabel", s.getState() == null ? null : s.getState().getLabel());
            payload.put("tradeMode", s.getTradeMode() == null ? null : s.getTradeMode().name());
            payload.put("instrumentKey", s.getVariety() == null ? null : s.instrumentKey());
            payload.put("ts", System.currentTimeMillis());
            wsPort.push("sim." + s.getSimId(), payload);
        } catch (Exception e) {
            // 推送模块由 BE-3 提供，未就绪时不能影响状态机本身
            log.debug("场次状态广播失败：{}", e.getMessage());
        }
    }
}
