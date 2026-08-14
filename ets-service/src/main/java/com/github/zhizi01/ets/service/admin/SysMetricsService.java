package com.github.zhizi01.ets.service.admin;

import com.github.zhizi01.ets.api.RobotPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.dto.SysMetricsVO;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.Quote;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.repo.RedisKeys;
import com.github.zhizi01.ets.repo.RedisStore;
import com.github.zhizi01.ets.repo.RobotRepo;
import com.github.zhizi01.ets.service.market.QuoteService;
import com.github.zhizi01.ets.ws.WsSessionRegistry;
import com.github.zhizi01.ets.ws.WsTopics;
import com.github.zhizi01.ets.api.WsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 系统运行指标：JVM、Redis、WebSocket 连接、各模拟盘的机器人与订单簿规模。
 * 同时负责 admin.dashboard.{simId} 的 2 秒一次监控推送。
 */
@Slf4j
@Service
public class SysMetricsService {

    private static final long MB = 1024 * 1024;

    private final RedisStore store;
    private final RobotRepo robotRepo;
    private final AccountRepo accountRepo;
    private final QuoteService quoteService;
    private final WsSessionRegistry registry;
    private final WsPort wsPort;
    private final SimPort simPort;
    private final RobotPort robotPort;

    public SysMetricsService(RedisStore store, RobotRepo robotRepo, AccountRepo accountRepo,
                             QuoteService quoteService, WsSessionRegistry registry, WsPort wsPort,
                             @Lazy SimPort simPort, @Lazy RobotPort robotPort) {
        this.store = store;
        this.robotRepo = robotRepo;
        this.accountRepo = accountRepo;
        this.quoteService = quoteService;
        this.registry = registry;
        this.wsPort = wsPort;
        this.simPort = simPort;
        this.robotPort = robotPort;
    }

    public SysMetricsVO metrics() {
        SysMetricsVO vo = new SysMetricsVO();
        fillJvm(vo.getJvm());
        fillRedis(vo.getRedis());
        fillWs(vo.getWs());
        try {
            for (Sim sim : simPort.listAll()) {
                vo.getSims().add(simMetrics(sim, true));
            }
        } catch (Exception e) {
            log.warn("采集模拟盘指标失败: {}", e.getMessage());
        }
        return vo;
    }

    /** 管理端监控大屏推送，只对有人订阅的盘做，避免空转 */
    @Scheduled(fixedDelay = 2000, initialDelay = 10_000)
    public void pushDashboard() {
        List<Sim> sims;
        try {
            sims = simPort.listAll();
        } catch (Exception e) {
            return;
        }
        for (Sim sim : sims) {
            String topic = WsTopics.adminDashboard(sim.getId());
            if (!registry.hasSubscribers(topic)) {
                continue;
            }
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                SysMetricsVO.SimMetrics m = simMetrics(sim, false);
                payload.put("sim", m);
                payload.put("online", registry.onlineCount());
                payload.put("quotes", topQuotes(sim.getId()));
                payload.put("ts", System.currentTimeMillis());
                wsPort.push(topic, "dashboard", payload);
            } catch (Exception e) {
                log.debug("监控推送失败 {}: {}", sim.getId(), e.getMessage());
            }
        }
    }

    // ── 采集 ────────────────────────────────────────────────

    private void fillJvm(SysMetricsVO.Jvm jvm) {
        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

            long used = mem.getHeapMemoryUsage().getUsed();
            long max = mem.getHeapMemoryUsage().getMax();
            jvm.setJavaVersion(System.getProperty("java.version"));
            jvm.setHeapUsedMb(used / MB);
            jvm.setHeapMaxMb(max <= 0 ? 0 : max / MB);
            jvm.setHeapCommittedMb(mem.getHeapMemoryUsage().getCommitted() / MB);
            jvm.setNonHeapUsedMb(mem.getNonHeapMemoryUsage().getUsed() / MB);
            jvm.setHeapUsedPct(max <= 0 ? 0 : Nums.round(used * 100.0 / max, 2));
            jvm.setThreadCount(threads.getThreadCount());
            jvm.setPeakThreadCount(threads.getPeakThreadCount());
            jvm.setDaemonThreadCount(threads.getDaemonThreadCount());
            jvm.setAvailableProcessors(os.getAvailableProcessors());
            jvm.setUptimeMs(runtime.getUptime());
            jvm.setSystemLoadAverage(Nums.round(os.getSystemLoadAverage(), 2));
        } catch (Exception e) {
            log.warn("采集 JVM 指标失败: {}", e.getMessage());
        }
    }

    private void fillRedis(SysMetricsVO.Redis redis) {
        try {
            Properties info = store.raw().execute((RedisCallback<Properties>) conn ->
                    conn.serverCommands().info());
            Long dbSize = store.raw().execute((RedisCallback<Long>) conn ->
                    conn.serverCommands().dbSize());
            redis.setConnected(true);
            redis.setDbSize(dbSize == null ? 0 : dbSize);
            if (info != null) {
                redis.setVersion(info.getProperty("redis_version"));
                redis.setMode(info.getProperty("redis_mode"));
                redis.setUsedMemoryHuman(info.getProperty("used_memory_human"));
                redis.setConnectedClients(asLong(info.getProperty("connected_clients")));
                redis.setUptimeSeconds(asLong(info.getProperty("uptime_in_seconds")));
                redis.setTotalCommandsProcessed(asLong(info.getProperty("total_commands_processed")));
                redis.setOpsPerSec(asLong(info.getProperty("instantaneous_ops_per_sec")));
            }
        } catch (Exception e) {
            redis.setConnected(false);
            redis.setError(e.getMessage());
            log.warn("采集 Redis 指标失败: {}", e.getMessage());
        }
    }

    private void fillWs(SysMetricsVO.Ws ws) {
        ws.setOnlineCount(registry.onlineCount());
        ws.setTopicCount(registry.topicCount());
        ws.setSubscriptionCount(registry.subscriptionCount());
        ws.setTotalConnected(registry.totalConnected());
    }

    private SysMetricsVO.SimMetrics simMetrics(Sim sim, boolean scanAllSessions) {
        SysMetricsVO.SimMetrics m = new SysMetricsVO.SimMetrics();
        m.setSimId(sim.getId());
        m.setSimName(sim.getName());
        m.setState(sim.getState() == null ? null : sim.getState().name());
        try {
            m.setAccountCount(accountRepo.idsBySim(sim.getId()).size());
            m.setRobotCount((int) robotRepo.countBySim(sim.getId()));
            m.setRobotRunning(robotPort.runningCount(sim.getId()));
        } catch (Exception e) {
            log.debug("采集机器人指标失败 {}: {}", sim.getId(), e.getMessage());
        }
        try {
            List<TradingSession> sessions = quoteService.allSessions(sim.getId());
            m.setSessionCount(sessions.size());
            long bids = 0;
            long asks = 0;
            int open = 0;
            double vol = 0;
            double amt = 0;
            for (TradingSession s : sessions) {
                vol += s.getTotalVolume();
                amt += s.getTotalAmount();
                boolean live = s.getState() != null && s.getState().acceptsOrders();
                if (live) {
                    open++;
                }
                // 全量扫描只在按需查询指标时做，2 秒一次的推送只统计还在接单的场次
                if (live || scanAllSessions) {
                    bids += store.zCard(RedisKeys.obBid(s.getId()));
                    asks += store.zCard(RedisKeys.obAsk(s.getId()));
                }
            }
            m.setOpenSessionCount(open);
            m.setBookBids(bids);
            m.setBookAsks(asks);
            m.setBookOrders(bids + asks);
            m.setTotalVolume(Nums.qty(vol));
            m.setTotalAmount(Nums.amount(amt));
        } catch (Exception e) {
            log.debug("采集场次指标失败 {}: {}", sim.getId(), e.getMessage());
        }
        if (sim.getState() == SimState.RUNNING) {
            m.getExtra().put("startedAt", sim.getStartedAt());
        }
        return m;
    }

    private List<Quote> topQuotes(String simId) {
        try {
            return quoteService.listBySim(simId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private long asLong(String v) {
        try {
            return v == null ? 0 : (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
