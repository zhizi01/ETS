package com.github.zhizi01.ets.robot;

import com.github.zhizi01.ets.api.AuditPort;
import com.github.zhizi01.ets.api.RobotPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.dto.RobotBatchReq;
import com.github.zhizi01.ets.domain.dto.RobotCreateReq;
import com.github.zhizi01.ets.domain.dto.RobotUpdateReq;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.repo.RedisStore;
import com.github.zhizi01.ets.repo.RobotRepo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 机器人引擎：负责生成、调度与启停。
 * <p>
 * 每个机器人是一个自我重排的单次任务而不是固定频率任务——固定频率任务在某一 tick 变慢时
 * 会堆积，而自排队天然做到"上一 tick 结束后再等一个带抖动的间隔"，也便于随时改参数。
 */
@Slf4j
@Component
public class RobotEngine implements RobotPort {

    /** 启动后延迟这么久再自动拉起机器人，给地基与模拟盘模块留出初始化时间 */
    private static final long AUTOSTART_DELAY_MS = 8_000;

    private final RobotRepo robotRepo;
    private final RobotFactory factory;
    private final RobotRunner runner;
    private final RedisStore store;
    private final EtsProperties props;
    private final ScheduledExecutorService scheduler;
    private final SimPort simPort;
    private final AuditPort auditPort;

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public RobotEngine(RobotRepo robotRepo,
                       RobotFactory factory,
                       RobotRunner runner,
                       RedisStore store,
                       EtsProperties props,
                       @Qualifier("robotScheduler") ScheduledExecutorService scheduler,
                       @Lazy SimPort simPort,
                       AuditPort auditPort) {
        this.robotRepo = robotRepo;
        this.factory = factory;
        this.runner = runner;
        this.store = store;
        this.props = props;
        this.scheduler = scheduler;
        this.simPort = simPort;
        this.auditPort = auditPort;
    }

    /** 一个机器人的调度槽位 */
    private static class Slot {
        private final String simId;
        private final RobotRunner.State state;
        private volatile boolean active = true;
        private volatile ScheduledFuture<?> future;

        Slot(String simId, RobotRunner.State state) {
            this.simId = simId;
            this.state = state;
        }
    }

    // ── RobotPort ───────────────────────────────────────────

    @Override
    public int spawnDefaultRobots(String simId) {
        Sim sim = simPort.requireSim(simId);
        long existing = robotRepo.countBySim(simId);
        if (existing > 0) {
            return (int) existing;
        }
        // 开盘动作可能被重复触发，用分布式锁 + 二次确认保证幂等
        Integer created = store.withLock("robot:spawn:" + simId, Duration.ofSeconds(60), 2000, () -> {
            if (robotRepo.countBySim(simId) > 0) {
                return (int) robotRepo.countBySim(simId);
            }
            int n = 0;
            for (RobotFactory.Spec spec : factory.defaultRoster()) {
                try {
                    factory.create(sim, spec, null, true);
                    n++;
                } catch (Exception e) {
                    log.warn("生成机器人 {} 失败: {}", spec.name(), e.getMessage());
                }
            }
            return n;
        });
        if (created == null) {
            // 没抢到锁说明别的线程正在生成，直接返回当前数量
            return (int) robotRepo.countBySim(simId);
        }
        log.info("模拟盘 {} 生成默认机器人 {} 台", simId, created);
        auditPort.system(simId, "生成默认机器人", simId, "共 " + created + " 台");
        return created;
    }

    @Override
    public void startSim(String simId) {
        if (!props.getRobot().isEnabled()) {
            log.info("ets.robot.enabled=false，跳过启动模拟盘 {} 的机器人", simId);
            return;
        }
        int n = 0;
        for (Robot r : safeList(simId)) {
            if (r.isEnabled() && schedule(r)) {
                n++;
            }
        }
        log.info("模拟盘 {} 启动机器人 {} 台", simId, n);
    }

    @Override
    public void stopSim(String simId) {
        int n = 0;
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            if (simId == null || simId.equals(e.getValue().simId)) {
                stop(e.getKey());
                n++;
            }
        }
        if (n > 0) {
            log.info("模拟盘 {} 停摆机器人 {} 台", simId, n);
        }
    }

    @Override
    public void setEnabled(String robotId, boolean enabled) {
        Robot r = robotRepo.require(robotId, ErrorCode.ROBOT_NOT_FOUND);
        r.setEnabled(enabled);
        r.setUpdatedAt(System.currentTimeMillis());
        robotRepo.save(r.getId(), r);
        if (enabled && isSimRunning(r.getSimId())) {
            schedule(r);
        } else {
            stop(robotId);
        }
    }

    @Override
    public int runningCount(String simId) {
        int n = 0;
        for (Slot s : slots.values()) {
            if (s.active && (simId == null || simId.equals(s.simId))) {
                n++;
            }
        }
        return n;
    }

    // ── 管理端能力 ──────────────────────────────────────────

    public List<Robot> listBySim(String simId) {
        return safeList(simId);
    }

    public boolean isRunning(String robotId) {
        Slot s = slots.get(robotId);
        return s != null && s.active;
    }

    public Robot create(RobotCreateReq req) {
        if (req == null || req.getSimId() == null || req.getSimId().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "simId 不能为空");
        }
        Sim sim = simPort.requireSim(req.getSimId());
        MemberType mt = req.getMemberType() == null ? MemberType.RETAILER : req.getMemberType();
        RobotStrategy st = req.getStrategy() == null ? RobotStrategy.NOISE : req.getStrategy();
        String name = req.getName() == null || req.getName().isBlank()
                ? mt.getLabel() + "机器人" + (robotRepo.countBySim(sim.getId()) + 1)
                : req.getName().trim();
        double capital = req.getInitialCapital() == null || req.getInitialCapital() <= 0
                ? factory.defaultCapital(mt) : req.getInitialCapital();

        RobotFactory.Spec spec = new RobotFactory.Spec(mt, st, name, req.getGenType(), req.getLoadProfile(), capital);
        boolean enabled = req.getEnabled() == null || req.getEnabled();
        Robot r = factory.create(sim, spec, req.getCfg(), enabled);
        if (enabled && isSimRunning(sim.getId())) {
            schedule(r);
        }
        auditPort.system(sim.getId(), "创建机器人", r.getId(), r.getName() + " / " + st.name());
        return r;
    }

    public int batch(RobotBatchReq req) {
        if (req == null || req.getSimId() == null || req.getSimId().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "simId 不能为空");
        }
        Sim sim = simPort.requireSim(req.getSimId());
        int offset = (int) robotRepo.countBySim(sim.getId());
        int count = req.getCount() == null ? 0 : Math.min(Math.max(req.getCount(), 0), 200);
        List<RobotFactory.Spec> specs = factory.expand(count, req.getStrategyMix(), req.getMemberTypeMix(),
                req.getCapitalMin() == null ? 0 : req.getCapitalMin(),
                req.getCapitalMax() == null ? 0 : req.getCapitalMax(),
                offset);
        boolean running = isSimRunning(sim.getId());
        int created = 0;
        for (RobotFactory.Spec spec : specs) {
            try {
                Robot r = factory.create(sim, spec, null, true);
                created++;
                if (running) {
                    schedule(r);
                }
            } catch (Exception e) {
                log.warn("批量生成机器人 {} 失败: {}", spec.name(), e.getMessage());
            }
        }
        auditPort.system(sim.getId(), "批量创建机器人", sim.getId(), "共 " + created + " 台");
        return created;
    }

    public Robot update(String robotId, RobotUpdateReq req) {
        Robot r = robotRepo.require(robotId, ErrorCode.ROBOT_NOT_FOUND);
        if (req != null) {
            if (req.getName() != null && !req.getName().isBlank()) {
                r.setName(req.getName().trim());
            }
            if (req.getStrategy() != null) {
                r.setStrategy(req.getStrategy());
            }
            if (req.getCfg() != null) {
                r.setCfg(normalize(req.getCfg()));
            }
            if (req.getEnabled() != null) {
                r.setEnabled(req.getEnabled());
            }
        }
        r.setUpdatedAt(System.currentTimeMillis());
        robotRepo.save(r.getId(), r);
        if (r.isEnabled() && isSimRunning(r.getSimId())) {
            schedule(r);
        } else {
            stop(robotId);
        }
        auditPort.system(r.getSimId(), "修改机器人", robotId, r.getName());
        return r;
    }

    public void delete(String robotId) {
        Robot r = robotRepo.require(robotId, ErrorCode.ROBOT_NOT_FOUND);
        stop(robotId);
        robotRepo.removeRobot(r);
        // 交易账户保留：它名下已有成交与资金流水，删掉会让历史对不上
        auditPort.system(r.getSimId(), "删除机器人", robotId, r.getName());
    }

    public int setAll(String simId, boolean enabled) {
        int n = 0;
        for (Robot r : safeList(simId)) {
            if (r.isEnabled() != enabled) {
                r.setEnabled(enabled);
                r.setUpdatedAt(System.currentTimeMillis());
                robotRepo.save(r.getId(), r);
            }
            if (enabled && isSimRunning(simId)) {
                schedule(r);
            } else if (!enabled) {
                stop(r.getId());
            }
            n++;
        }
        auditPort.system(simId, enabled ? "启用全部机器人" : "停用全部机器人", simId, "共 " + n + " 台");
        return n;
    }

    public RobotConfig defaultCfg(RobotStrategy strategy) {
        return factory.defaultCfg(strategy);
    }

    public List<String> suitableMemberTypes(RobotStrategy strategy) {
        return factory.suitableMemberTypes(strategy);
    }

    // ── 启动自愈 ────────────────────────────────────────────

    /**
     * 进程重启后，已经处于 RUNNING 的盘必须自动把机器人拉起来，
     * 否则演练会在无人察觉的情况下变成一潭死水。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!props.getRobot().isEnabled()) {
            return;
        }
        scheduler.schedule(this::autoStart, AUTOSTART_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void autoStart() {
        try {
            List<Sim> running = simPort.listRunning();
            if (running.isEmpty()) {
                return;
            }
            for (Sim sim : running) {
                try {
                    if (robotRepo.countBySim(sim.getId()) == 0) {
                        spawnDefaultRobots(sim.getId());
                    }
                    startSim(sim.getId());
                } catch (Exception e) {
                    log.warn("自动拉起模拟盘 {} 的机器人失败: {}", sim.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("机器人自动拉起失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        for (String id : new ArrayList<>(slots.keySet())) {
            stop(id);
        }
    }

    // ── 调度 ────────────────────────────────────────────────

    private boolean schedule(Robot r) {
        if (r == null || r.getId() == null) {
            return false;
        }
        final boolean[] started = {false};
        slots.compute(r.getId(), (id, old) -> {
            if (old != null && old.active) {
                return old;
            }
            Slot slot = new Slot(r.getSimId(), runner.newState(r));
            started[0] = true;
            // 首次延迟随机铺开，避免几十个机器人在同一毫秒集体开火
            long delay = ThreadLocalRandom.current().nextLong(200, Math.max(nextInterval(slot), 400));
            slot.future = submit(id, slot, delay);
            return slot;
        });
        return started[0];
    }

    private void stop(String robotId) {
        Slot slot = slots.remove(robotId);
        if (slot != null) {
            slot.active = false;
            if (slot.future != null) {
                slot.future.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> submit(String robotId, Slot slot, long delayMs) {
        try {
            return scheduler.schedule(() -> run(robotId, slot), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            slot.active = false;
            return null;
        }
    }

    private void run(String robotId, Slot slot) {
        if (!slot.active) {
            return;
        }
        boolean keepGoing = true;
        try {
            keepGoing = runner.tick(slot.state);
        } catch (Throwable t) {
            // 任何异常都不能中断循环，否则这个机器人从此再也不动了
            log.debug("机器人 {} tick 异常: {}", robotId, t.toString());
        }
        if (!keepGoing) {
            stop(robotId);
            return;
        }
        if (slot.active && slots.get(robotId) == slot) {
            slot.future = submit(robotId, slot, nextInterval(slot));
        }
    }

    /** tick 间隔 ±30% 抖动，并受全局最小间隔约束 */
    private long nextInterval(Slot slot) {
        long base = slot.state.tickIntervalMs();
        if (base <= 0) {
            base = 3000;
        }
        double jitter = 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
        long min = Math.max(props.getRobot().getMinTickIntervalMs(), 100);
        return Math.max(min, (long) (base * jitter));
    }

    private boolean isSimRunning(String simId) {
        try {
            Sim sim = simPort.findSim(simId);
            return sim != null && sim.getState() == SimState.RUNNING;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Robot> safeList(String simId) {
        try {
            List<Robot> list = robotRepo.listBySim(simId);
            list.removeIf(java.util.Objects::isNull);
            return list;
        } catch (Exception e) {
            log.warn("读取机器人列表失败 {}: {}", simId, e.getMessage());
            return List.of();
        }
    }

    /** 管理端可能传进离谱的参数，这里兜一层底线 */
    private RobotConfig normalize(RobotConfig c) {
        c.setTickIntervalMs(Math.max(c.getTickIntervalMs(), props.getRobot().getMinTickIntervalMs()));
        c.setAggressiveness(clamp01(c.getAggressiveness(), 0.5));
        c.setActProbability(clamp01(c.getActProbability(), 0.6));
        c.setCancelProbability(clamp01(c.getCancelProbability(), 0.25));
        c.setListingProbability(clamp01(c.getListingProbability(), 0.2));
        if (c.getMaxOrderMwh() <= 0) {
            c.setMaxOrderMwh(3000);
        }
        if (c.getSpreadPct() <= 0) {
            c.setSpreadPct(0.012);
        }
        return c;
    }

    private double clamp01(double v, double fallback) {
        if (Double.isNaN(v) || v < 0 || v > 1) {
            return fallback;
        }
        return v;
    }
}
