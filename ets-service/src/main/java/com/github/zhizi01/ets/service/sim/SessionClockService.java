package com.github.zhizi01.ets.service.sim;

import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.repo.TradingSessionRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场次时钟。每秒扫描一次交易中的模拟盘，按真实时间把场次推过状态机：
 * <pre>
 *   连续交易类   PENDING → OPEN → CLOSED
 *   集中出清类   PENDING → PRE_OPEN → CLEARING → CLOSED
 * </pre>
 * 时钟严格 1:1 实时（{@code sim.clockSpeed} 为保留字段，当前按 1 处理）。
 * 只扫 RUNNING 的盘，因此暂停演练时时间轴自然停摆，恢复后从当前真实时间继续。
 * <p>SUSPENDED 是管理员的人工干预，不由时钟自动恢复。
 */
@Slf4j
@Service
public class SessionClockService {

    private final SimRepo simRepo;
    private final TradingSessionRepo sessionRepo;
    private final TradingSessionService sessionService;
    private final WsPort wsPort;

    public SessionClockService(SimRepo simRepo,
                               TradingSessionRepo sessionRepo,
                               TradingSessionService sessionService,
                               @Lazy WsPort wsPort) {
        this.simRepo = simRepo;
        this.sessionRepo = sessionRepo;
        this.sessionService = sessionService;
        this.wsPort = wsPort;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        try {
            for (Sim sim : simRepo.listAll()) {
                if (sim.getState() != SimState.RUNNING) {
                    continue;
                }
                advanceSim(sim);
            }
        } catch (Exception e) {
            // 时钟绝不能因为一次 Redis 抖动而停摆
            log.warn("场次时钟本轮扫描异常：{}", e.getMessage());
        }
    }

    private void advanceSim(Sim sim) {
        long now = System.currentTimeMillis();
        for (TradingSession s : sessionRepo.listBySim(sim.getId())) {
            try {
                advanceSession(s, now);
            } catch (Exception e) {
                log.warn("场次 {} 自动推进失败：{}", s.getId(), e.getMessage());
            }
        }
    }

    private void advanceSession(TradingSession s, long now) {
        TSessionState state = s.getState() == null ? TSessionState.PENDING : s.getState();
        boolean centralized = s.getTradeMode() != null && s.getTradeMode().isCentralized();

        switch (state) {
            case PENDING -> {
                if (now >= startTimeOf(s, centralized)) {
                    if (centralized) {
                        sessionService.preOpen(s.getId());
                        notice(s, "集中申报开始，只接受申报不即时撮合");
                    } else {
                        sessionService.open(s.getId());
                        notice(s, "场次开市，开始接受申报");
                    }
                }
            }
            case PRE_OPEN -> {
                // 申报窗口结束即统一出清，clear 内部会一路推到 CLOSED
                if (now >= s.getCloseTime()) {
                    notice(s, "申报截止，开始统一出清");
                    sessionService.clear(s.getId());
                    noticeClosed(s);
                }
            }
            case OPEN -> {
                if (now >= s.getCloseTime()) {
                    sessionService.close(s.getId());
                    noticeClosed(s);
                }
            }
            default -> {
                // SUSPENDED 等待管理员处置，CLEARING 由 clear 自己收尾，CLOSED/SETTLED 已终态
            }
        }
    }

    /** 集中出清类以申报开始时刻为起点，其余以开市时刻为起点。 */
    private static long startTimeOf(TradingSession s, boolean centralized) {
        if (centralized && s.getPreOpenTime() > 0) {
            return Math.min(s.getPreOpenTime(), s.getOpenTime() > 0 ? s.getOpenTime() : Long.MAX_VALUE);
        }
        return s.getOpenTime();
    }

    private void noticeClosed(TradingSession s) {
        TradingSession latest = sessionRepo.find(s.getId());
        TradeMode mode = s.getTradeMode();
        String msg = mode != null && mode.isCentralized() && latest != null && latest.getClearPrice() > 0
                ? String.format("出清完成，统一出清价 %.2f 元/MWh，场次已闭市", latest.getClearPrice())
                : "场次已闭市";
        notice(latest == null ? s : latest, msg);
    }

    /** 状态变更向 sim.{simId} 主题广播，前端的场次卡片与倒计时据此刷新。 */
    private void notice(TradingSession s, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "session.clock");
            payload.put("sessionId", s.getId());
            payload.put("sessionName", s.getName());
            payload.put("state", s.getState() == null ? null : s.getState().name());
            payload.put("stateLabel", s.getState() == null ? null : s.getState().getLabel());
            payload.put("instrumentKey", s.getVariety() == null ? null : s.instrumentKey());
            payload.put("message", message);
            payload.put("ts", System.currentTimeMillis());
            wsPort.push("sim." + s.getSimId(), payload);
        } catch (Exception e) {
            log.debug("场次时钟广播失败：{}", e.getMessage());
        }
    }
}
