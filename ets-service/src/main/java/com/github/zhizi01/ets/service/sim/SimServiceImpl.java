package com.github.zhizi01.ets.service.sim;

import com.github.zhizi01.ets.api.ClearingPort;
import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.api.RobotPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.dto.SimCreateReq;
import com.github.zhizi01.ets.domain.dto.SimDashboardVO;
import com.github.zhizi01.ets.domain.dto.SimRankVO;
import com.github.zhizi01.ets.domain.dto.SimUpdateReq;
import com.github.zhizi01.ets.domain.dto.SimVO;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.model.AccountStat;
import com.github.zhizi01.ets.domain.model.FundInfo;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.repo.RedisKeys;
import com.github.zhizi01.ets.repo.RedisStore;
import com.github.zhizi01.ets.repo.RobotRepo;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.repo.TradingSessionRepo;
import com.github.zhizi01.ets.service.account.TradingAccountServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 模拟盘（演练场）的生命周期。
 * <p>整个系统的时间线由这里驱动：SETUP 让用户自由建号，start 一次性完成
 * 注资与参数锁定并唤起机器人，之后所有交易数据都挂在这个 simId 下，
 * reset 则把它清回原点以便反复演练。
 */
@Slf4j
@Service
public class SimServiceImpl implements SimPort {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SimRepo simRepo;
    private final AccountRepo accountRepo;
    private final TradingSessionRepo sessionRepo;
    private final RobotRepo robotRepo;
    private final RedisStore store;
    private final IdGen idGen;
    private final FundPort fundPort;
    private final TradingAccountServiceImpl accountService;
    private final TradingSessionService sessionService;
    private final SessionPresetFactory presetFactory;
    private final RobotPort robotPort;
    private final ClearingPort clearingPort;
    private final WsPort wsPort;

    public SimServiceImpl(SimRepo simRepo,
                          AccountRepo accountRepo,
                          TradingSessionRepo sessionRepo,
                          RobotRepo robotRepo,
                          RedisStore store,
                          IdGen idGen,
                          FundPort fundPort,
                          TradingAccountServiceImpl accountService,
                          TradingSessionService sessionService,
                          SessionPresetFactory presetFactory,
                          @Lazy RobotPort robotPort,
                          @Lazy ClearingPort clearingPort,
                          @Lazy WsPort wsPort) {
        this.simRepo = simRepo;
        this.accountRepo = accountRepo;
        this.sessionRepo = sessionRepo;
        this.robotRepo = robotRepo;
        this.store = store;
        this.idGen = idGen;
        this.fundPort = fundPort;
        this.accountService = accountService;
        this.sessionService = sessionService;
        this.presetFactory = presetFactory;
        this.robotPort = robotPort;
        this.clearingPort = clearingPort;
        this.wsPort = wsPort;
    }

    // ══════════════════════════ SimPort ══════════════════════════

    @Override
    public Sim findSim(String simId) {
        return simRepo.find(simId);
    }

    @Override
    public Sim requireSim(String simId) {
        return simRepo.require(simId, ErrorCode.SIM_NOT_FOUND);
    }

    @Override
    public Sim requireRunning(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() != SimState.RUNNING) {
            throw BizException.of(ErrorCode.SIM_NOT_RUNNING,
                    "模拟盘当前为「" + sim.getState().getLabel() + "」，暂不接受交易");
        }
        return sim;
    }

    @Override
    public List<Sim> listAll() {
        return simRepo.listAll();
    }

    @Override
    public List<Sim> listRunning() {
        List<Sim> out = new ArrayList<>();
        for (Sim s : simRepo.listAll()) {
            if (s.getState() == SimState.RUNNING) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public TradingSession findSession(String sessionId) {
        return sessionService.find(sessionId);
    }

    @Override
    public TradingSession requireSession(String sessionId) {
        return sessionService.require(sessionId);
    }

    @Override
    public List<TradingSession> listSessions(String simId) {
        return sessionService.listBySim(simId);
    }

    @Override
    public List<TradingSession> listOpenSessions(String simId) {
        return sessionService.listOpen(simId);
    }

    @Override
    public void saveSession(TradingSession session) {
        sessionService.save(session);
    }

    // ══════════════════════════ 增删改 ══════════════════════════

    public Sim create(SimCreateReq req, String adminId) {
        Sim sim = new Sim();
        sim.setId(idGen.next(IdGen.SIM));
        sim.setName(req.getName().trim());
        sim.setDescription(req.getDescription());
        sim.setState(SimState.SETUP);
        sim.setOwnerAdminId(adminId);
        sim.setCreatedAt(System.currentTimeMillis());
        sim.setClockSpeed(1.0);
        sim.setBaseDate(req.getBaseDate() == null || req.getBaseDate().isBlank()
                ? LocalDate.now().format(DATE) : req.getBaseDate().trim());
        sim.setCfg(req.getCfg() == null ? new SimConfig() : req.getCfg());
        validateCfg(sim.getCfg());
        simRepo.saveSim(sim);
        log.info("创建模拟盘 {} {}", sim.getId(), sim.getName());

        if (req.isGenerateStandardSessions()) {
            presetFactory.generateStandard(sim.getId());
        }
        return sim;
    }

    public Sim update(String simId, SimUpdateReq req) {
        Sim sim = requireSim(simId);
        if (req.getName() != null && !req.getName().isBlank()) {
            sim.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            sim.setDescription(req.getDescription());
        }
        if (req.getBaseDate() != null && !req.getBaseDate().isBlank()) {
            sim.setBaseDate(req.getBaseDate().trim());
        }
        if (req.getCfg() != null) {
            if (sim.getState() != SimState.SETUP) {
                throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL,
                        "开盘后不能修改交易规则，如需调整请先重置演练");
            }
            validateCfg(req.getCfg());
            sim.setCfg(req.getCfg());
        }
        // clockSpeed 为保留字段，当前一律 1:1 实时推进
        sim.setClockSpeed(1.0);
        simRepo.saveSim(sim);
        return sim;
    }

    public void delete(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() == SimState.RUNNING) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL, "交易中的模拟盘不能删除，请先结束演练");
        }
        purgeSimData(sim);
        simRepo.removeSim(simId);
        log.info("删除模拟盘 {} {}", simId, sim.getName());
    }

    // ══════════════════════════ 状态机 ══════════════════════════

    /**
     * 开盘。这是全场唯一一次性把「参数」变成「事实」的动作：
     * 校验 → 注资 → 锁定参数 → 重排场次时间轴 → 唤起机器人。
     * 任何一步失败都直接抛出，不留半开状态。
     */
    public Sim start(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() != SimState.SETUP) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL,
                    "只有「筹备中」的模拟盘可以开盘，当前为「" + sim.getState().getLabel() + "」");
        }
        List<TradingAccount> accounts = accountRepo.listBySim(simId);
        for (TradingAccount a : accounts) {
            accountService.assertReadyForStart(a);
        }

        long now = System.currentTimeMillis();
        int funded = 0;
        for (TradingAccount a : accounts) {
            if (a.isParamsLocked()) {
                continue;
            }
            a.setParamsLocked(true);
            a.setLockedAt(now);
            accountService.save(a);
            double capital = a.getParams() == null ? 0 : a.getParams().getInitialCapital();
            if (capital > 0) {
                fundPort.deposit(a.getId(), capital, "开盘初始注资");
            }
            simRepo.updateRank(simId, a.getId(), 0);
            funded++;
        }

        // 必须在切到 RUNNING 之前重排时间轴：场次时钟只扫 RUNNING 的盘，
        // 一旦先置位，时钟就会抢在重排之前把这批过期场次全部开市。
        int rebased = sessionService.rebaseClock(simId, System.currentTimeMillis());

        sim.setState(SimState.RUNNING);
        sim.setStartedAt(System.currentTimeMillis());
        simRepo.saveSim(sim);

        int robots = spawnRobots(simId);
        pushSim(sim, "start", "演练开盘");
        log.info("模拟盘 {} 开盘：注资账户 {} 个，重排场次 {} 个，机器人 {} 个",
                simId, funded, rebased, robots);
        return sim;
    }

    public Sim pause(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() != SimState.RUNNING) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL, "只有交易中的模拟盘可以暂停");
        }
        sim.setState(SimState.PAUSED);
        simRepo.saveSim(sim);
        quietly("停摆机器人", () -> robotPort.stopSim(simId));
        pushSim(sim, "pause", "演练暂停");
        return sim;
    }

    public Sim resume(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() != SimState.PAUSED) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL, "只有已暂停的模拟盘可以恢复");
        }
        sim.setState(SimState.RUNNING);
        simRepo.saveSim(sim);
        quietly("唤起机器人", () -> robotPort.startSim(simId));
        pushSim(sim, "resume", "演练恢复");
        return sim;
    }

    public Sim end(String simId) {
        Sim sim = requireSim(simId);
        if (sim.getState() != SimState.RUNNING && sim.getState() != SimState.PAUSED) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL, "只有进行中的模拟盘可以结束");
        }
        quietly("停摆机器人", () -> robotPort.stopSim(simId));
        // 先闭掉还开着的场次，避免结算时还有未撤销的挂单占着资金
        for (TradingSession s : sessionRepo.listBySim(simId)) {
            if (s.getState() != null && s.getState() != TSessionState.CLOSED
                    && s.getState() != TSessionState.SETTLED) {
                quietly("闭市场次 " + s.getId(), () -> sessionService.close(s.getId()));
            }
        }
        sim.setState(SimState.ENDED);
        sim.setEndedAt(System.currentTimeMillis());
        simRepo.saveSim(sim);
        quietly("终盘结算", () -> clearingPort.settle(simId, "FINAL"));
        pushSim(sim, "end", "演练结束，已触发终盘结算");
        log.info("模拟盘 {} 已结束", simId);
        return sim;
    }

    public Sim reset(String simId) {
        Sim sim = requireSim(simId);
        quietly("停摆机器人", () -> robotPort.stopSim(simId));
        purgeSimData(sim);
        sim.setState(SimState.SETUP);
        sim.setStartedAt(0);
        sim.setEndedAt(0);
        simRepo.saveSim(sim);
        pushSim(sim, "reset", "演练已重置，回到筹备中");
        log.info("模拟盘 {} 已重置为筹备中", simId);
        return sim;
    }

    // ══════════════════════════ 视图与统计 ══════════════════════════

    public SimVO toVO(Sim sim) {
        SimVO vo = SimVO.of(sim);
        if (vo == null) {
            return null;
        }
        List<TradingAccount> accounts = accountRepo.listBySim(sim.getId());
        Set<String> users = new HashSet<>();
        long human = 0;
        long robot = 0;
        for (TradingAccount a : accounts) {
            if (a.getSource() == OrderSource.ROBOT) {
                robot++;
            } else {
                human++;
                if (a.getUserId() != null) {
                    users.add(a.getUserId());
                }
            }
        }
        vo.setAccountCount(accounts.size());
        vo.setHumanAccountCount(human);
        vo.setRobotAccountCount(robot);
        vo.setUserCount(users.size());

        List<TradingSession> sessions = sessionRepo.listBySim(sim.getId());
        long open = sessions.stream()
                .filter(s -> s.getState() != null && s.getState().acceptsOrders())
                .count();
        vo.setSessionCount(sessions.size());
        vo.setOpenSessionCount(open);
        return vo;
    }

    public List<SimVO> toVOList(List<Sim> sims) {
        List<SimVO> out = new ArrayList<>(sims.size());
        for (Sim s : sims) {
            out.add(toVO(s));
        }
        return out;
    }

    /** 排行榜：以 ets:sim:{id}:rank 的 totalPnl 为序，回填账户明细。 */
    public List<SimRankVO> rank(String simId, int limit) {
        List<String> ids = simRepo.topAccounts(simId, Math.max(limit, 1));
        List<SimRankVO> out = new ArrayList<>(ids.size());
        int i = 0;
        for (String accountId : ids) {
            TradingAccount a = accountRepo.find(accountId);
            if (a == null) {
                continue;
            }
            AccountStat s = a.getStat() == null ? new AccountStat() : a.getStat();
            FundInfo f = a.getFund() == null ? new FundInfo() : a.getFund();
            SimRankVO vo = new SimRankVO();
            vo.setRank(++i);
            vo.setAccountId(a.getId());
            vo.setCode(a.getCode());
            vo.setName(a.getName());
            vo.setMemberType(a.getMemberType());
            vo.setMemberTypeLabel(a.getMemberType() == null ? null : a.getMemberType().getLabel());
            vo.setSource(a.getSource());
            vo.setSourceLabel(a.getSource() == null ? null : a.getSource().getLabel());
            vo.setTotalPnl(Nums.amount(s.getTotalPnl()));
            vo.setRealizedPnl(Nums.amount(s.getRealizedPnl()));
            vo.setUnrealizedPnl(Nums.amount(s.getUnrealizedPnl()));
            vo.setReturnPct(f.getTotalDeposit() <= 0 ? 0 : Nums.round(s.getTotalPnl() / f.getTotalDeposit(), 6));
            vo.setTradeCount(s.getTradeCount());
            vo.setBuyMwh(Nums.qty(s.getBuyMwh()));
            vo.setSellMwh(Nums.qty(s.getSellMwh()));
            out.add(vo);
        }
        return out;
    }

    public SimDashboardVO dashboard(String simId) {
        Sim sim = requireSim(simId);
        SimDashboardVO vo = new SimDashboardVO();
        vo.setSimId(sim.getId());
        vo.setSimName(sim.getName());
        vo.setState(sim.getState());
        vo.setStateLabel(sim.getState() == null ? null : sim.getState().getLabel());
        vo.setStartedAt(sim.getStartedAt());
        vo.setServerTime(System.currentTimeMillis());

        // ── 参与规模与主体分布 ──
        List<TradingAccount> accounts = accountRepo.listBySim(simId);
        Set<String> users = new HashSet<>();
        long human = 0;
        long robotAcct = 0;
        Map<MemberType, long[]> dist = new EnumMap<>(MemberType.class);
        Map<MemberType, Double> distCapital = new EnumMap<>(MemberType.class);
        for (TradingAccount a : accounts) {
            if (a.getSource() == OrderSource.ROBOT) {
                robotAcct++;
            } else {
                human++;
                if (a.getUserId() != null) {
                    users.add(a.getUserId());
                }
            }
            MemberType t = a.getMemberType();
            if (t != null) {
                dist.computeIfAbsent(t, k -> new long[1])[0]++;
                FundInfo f = a.getFund() == null ? new FundInfo() : a.getFund();
                distCapital.merge(t, f.total(), Double::sum);
            }
        }
        vo.setAccountCount(accounts.size());
        vo.setHumanAccountCount(human);
        vo.setRobotAccountCount(robotAcct);
        vo.setUserCount(users.size());
        for (MemberType t : MemberType.values()) {
            long n = dist.containsKey(t) ? dist.get(t)[0] : 0;
            vo.getMemberTypeDist().add(SimDashboardVO.Bucket.of(
                    t.name(), t.getLabel(), n, Nums.amount(distCapital.getOrDefault(t, 0d))));
        }

        vo.setRobotCount(robotRepo.countBySim(simId));
        vo.setRunningRobotCount(intQuietly(() -> robotPort.runningCount(simId)));
        vo.setWsOnlineCount(intQuietly(wsPort::onlineCount));

        // ── 场次与成交 ──
        List<TradingSession> sessions = sessionRepo.listBySim(simId);
        Map<TSessionState, long[]> stateDist = new EnumMap<>(TSessionState.class);
        double volume = 0;
        double amount = 0;
        long open = 0;
        for (TradingSession s : sessions) {
            TSessionState st = s.getState() == null ? TSessionState.PENDING : s.getState();
            stateDist.computeIfAbsent(st, k -> new long[1])[0]++;
            if (st.acceptsOrders()) {
                open++;
            }
            volume += s.getTotalVolume();
            amount += s.getTotalAmount();
        }
        vo.setSessionCount(sessions.size());
        vo.setOpenSessionCount(open);
        for (TSessionState st : TSessionState.values()) {
            long n = stateDist.containsKey(st) ? stateDist.get(st)[0] : 0;
            if (n > 0) {
                vo.getSessionStateDist().add(SimDashboardVO.Bucket.of(st.name(), st.getLabel(), n, 0));
            }
        }
        vo.setTotalVolume(Nums.qty(volume));
        vo.setTotalAmount(Nums.amount(amount));
        vo.setAvgPrice(Nums.price(Nums.div(amount, volume)));

        long tradeCount = 0;
        for (TradingSession s : sessions) {
            tradeCount += store.zCard(RedisKeys.sessTrades(s.getId()));
        }
        vo.setTradeCount(tradeCount);

        vo.setTop10(rank(simId, 10));
        vo.setAlerts(buildAlerts(sim, accounts, open));
        return vo;
    }

    private List<String> buildAlerts(Sim sim, List<TradingAccount> accounts, long openSessions) {
        List<String> alerts = new ArrayList<>();
        if (sim.getState() == SimState.RUNNING && openSessions == 0) {
            alerts.add("当前没有任何可交易场次，请检查场次时间轴或手工开市");
        }
        int broke = 0;
        int deepLoss = 0;
        for (TradingAccount a : accounts) {
            FundInfo f = a.getFund() == null ? new FundInfo() : a.getFund();
            AccountStat s = a.getStat() == null ? new AccountStat() : a.getStat();
            if (f.getTotalDeposit() > 0 && f.getAvailable() < f.getTotalDeposit() * 0.05) {
                broke++;
            }
            if (f.getTotalDeposit() > 0 && s.getTotalPnl() < -f.getTotalDeposit() * 0.2) {
                deepLoss++;
            }
        }
        if (broke > 0) {
            alerts.add("有 " + broke + " 个账户可用资金低于初始注资的 5%，已基本丧失购电能力");
        }
        if (deepLoss > 0) {
            alerts.add("有 " + deepLoss + " 个账户亏损超过初始注资的 20%，建议关注其报价策略");
        }
        return alerts;
    }

    // ══════════════════════════ 内部工具 ══════════════════════════

    private void validateCfg(SimConfig cfg) {
        if (cfg.getMaxAccountsPerUser() < 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "每人最多可创建账户数至少为 1");
        }
        if (cfg.getInitialCapitalMin() <= 0 || cfg.getInitialCapitalMax() < cfg.getInitialCapitalMin()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "初始资金区间不合法");
        }
        if (cfg.getBenchmarkPrice() <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "基准电价必须为正数");
        }
        if (cfg.getPriceLimitPct() <= 0 || cfg.getPriceLimitPct() > 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "涨跌幅限制需在 (0,1] 之间");
        }
        if (cfg.getMarginRatio() < 0 || cfg.getMarginRatio() > 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "保证金比例需在 [0,1] 之间");
        }
    }

    /**
     * 清空模拟盘下的交易数据。实体键（委托/成交/合同等）是全局命名的，
     * 不能用通配删除，只能顺着各自的索引逐个回收，否则会误删其它演练场的数据。
     */
    private void purgeSimData(Sim sim) {
        String simId = sim.getId();
        for (TradingSession s : sessionRepo.listBySim(simId)) {
            deleteByIndex(RedisKeys.sessOrders(s.getId()), RedisKeys::order);
            deleteByIndex(RedisKeys.sessTrades(s.getId()), RedisKeys::trade);
            deleteByIndex(RedisKeys.sessListings(s.getId()), RedisKeys::listing);
            deleteByIndex(RedisKeys.sessSpotBids(s.getId()), RedisKeys::spotBid);
            store.deleteByPattern(RedisKeys.P + "tsess:" + s.getId() + ":*");
            sessionRepo.removeSession(s);
        }
        for (TradingAccount a : accountRepo.listBySim(simId)) {
            deleteByIndex(RedisKeys.acctContracts(a.getId()), RedisKeys::contract);
            deleteByIndex(RedisKeys.acctBills(a.getId()), RedisKeys::bill);
            deleteByIndex(RedisKeys.acctNegos(a.getId()), RedisKeys::nego);
            accountService.purgeAccountData(a);
        }
        for (Robot r : robotRepo.listBySim(simId)) {
            robotRepo.removeRobot(r);
        }
        simRepo.purge(simId);
    }

    private void deleteByIndex(String indexKey, UnaryOperator<String> entityKey) {
        List<String> ids = store.zRevRangeAll(indexKey);
        for (String id : ids) {
            store.delete(entityKey.apply(id));
        }
        store.delete(indexKey);
    }

    private int spawnRobots(String simId) {
        try {
            int n = robotPort.spawnDefaultRobots(simId);
            robotPort.startSim(simId);
            return n;
        } catch (Exception e) {
            log.warn("机器人模块尚未就绪，本次开盘未生成机器人：{}", e.getMessage());
            return 0;
        }
    }

    /** 跨模块调用一律降级处理：BE-2/BE-3 的实现缺席时不能挡住演练主流程。 */
    private void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("{}失败：{}", what, e.getMessage());
        }
    }

    private int intQuietly(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private void pushSim(Sim sim, String event, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "sim." + event);
            payload.put("simId", sim.getId());
            payload.put("simName", sim.getName());
            payload.put("state", sim.getState() == null ? null : sim.getState().name());
            payload.put("stateLabel", sim.getState() == null ? null : sim.getState().getLabel());
            payload.put("message", message);
            payload.put("ts", System.currentTimeMillis());
            wsPort.push("sim." + sim.getId(), payload);
        } catch (Exception e) {
            log.debug("模拟盘状态广播失败：{}", e.getMessage());
        }
    }
}
