package com.github.zhizi01.ets.service.account;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.dto.AccountVO;
import com.github.zhizi01.ets.domain.dto.AcctCreateReq;
import com.github.zhizi01.ets.domain.dto.AcctSummaryVO;
import com.github.zhizi01.ets.domain.dto.AcctUpdateReq;
import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.AccountStat;
import com.github.zhizi01.ets.domain.model.FundInfo;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.User;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.repo.PositionRepo;
import com.github.zhizi01.ets.repo.RedisKeys;
import com.github.zhizi01.ets.repo.RedisStore;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易账户模块。
 * <p>参数的可编辑窗口只有模拟盘的 SETUP 阶段：开盘时 {@code paramsLocked} 被置位，
 * 此后 params 直接决定了成本线、可售电量与盈亏口径，一旦允许中途修改，
 * 排行榜就失去了可比性，因此所有写操作都要先过 {@link #requireEditable}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingAccountServiceImpl implements AccountPort {

    private final AccountRepo accountRepo;
    private final SimRepo simRepo;
    private final UserRepo userRepo;
    private final PositionRepo positionRepo;
    private final RedisStore store;
    private final IdGen idGen;
    private final FundPort fundPort;

    // ══════════════════════════ AccountPort ══════════════════════════

    @Override
    public TradingAccount find(String accountId) {
        return accountRepo.find(accountId);
    }

    @Override
    public TradingAccount require(String accountId) {
        return accountRepo.require(accountId, ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Override
    public TradingAccount requireOwned(String accountId, String userId) {
        TradingAccount a = require(accountId);
        if (userId == null || !userId.equals(a.getUserId())) {
            throw BizException.of(ErrorCode.ACCOUNT_NOT_OWNED);
        }
        return a;
    }

    @Override
    public TradingAccount requireTradable(String accountId) {
        TradingAccount a = require(accountId);
        Sim sim = simRepo.require(a.getSimId(), ErrorCode.SIM_NOT_FOUND);
        if (sim.getState() != SimState.RUNNING) {
            throw BizException.of(ErrorCode.SIM_NOT_RUNNING,
                    "模拟盘当前为「" + sim.getState().getLabel() + "」，暂不接受申报");
        }
        if (a.isFrozen()) {
            throw BizException.of(ErrorCode.USER_FROZEN, "该交易账户已被管理员冻结");
        }
        if (!a.isParamsLocked()) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED, "该账户尚未完成开盘注资，不能交易");
        }
        return a;
    }

    @Override
    public List<TradingAccount> listBySim(String simId) {
        List<TradingAccount> all = accountRepo.listBySim(simId);
        all.sort(Comparator.comparing(TradingAccount::getCode, Comparator.nullsLast(String::compareTo)));
        return all;
    }

    @Override
    public List<TradingAccount> listBySim(String simId, MemberType memberType) {
        List<TradingAccount> out = new ArrayList<>();
        for (TradingAccount a : listBySim(simId)) {
            if (memberType == null || memberType == a.getMemberType()) {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public void save(TradingAccount account) {
        account.setUpdatedAt(System.currentTimeMillis());
        accountRepo.saveAccount(account);
    }

    @Override
    public TradingAccount createRobotAccount(String simId, MemberType memberType, RobotStrategy strategy,
                                             String name, String robotId, double initialCapital) {
        Sim sim = simRepo.require(simId, ErrorCode.SIM_NOT_FOUND);
        SimConfig cfg = cfgOf(sim);
        double capital = Nums.amount(Nums.clamp(initialCapital,
                cfg.getInitialCapitalMin(), cfg.getInitialCapitalMax()));

        TradingAccount a = new TradingAccount();
        a.setId(idGen.next(IdGen.ACCOUNT));
        a.setSimId(simId);
        a.setUserId(null);
        a.setCode(nextCode(simId, memberType));
        a.setName(name == null || name.isBlank() ? memberType.getLabel() + "机器人" : name);
        a.setMemberType(memberType);
        a.setSource(OrderSource.ROBOT);
        a.setRobotId(robotId);
        a.setParams(robotParams(memberType, strategy, capital, cfg));
        a.setFund(new FundInfo());
        a.setStat(new AccountStat());
        // 机器人不经历 SETUP 编辑期，建号即锁定并注资，可以立刻参与撮合
        a.setParamsLocked(true);
        a.setLockedAt(System.currentTimeMillis());
        a.setCreatedAt(System.currentTimeMillis());
        save(a);

        fundPort.deposit(a.getId(), capital, "机器人账户初始注资");
        simRepo.updateRank(simId, a.getId(), 0);
        return accountRepo.find(a.getId());
    }

    // ══════════════════════════ 用户侧增删改查 ══════════════════════════

    public List<TradingAccount> listMine(String userId, String simId) {
        List<TradingAccount> all = accountRepo.listByUser(userId, simId);
        all.sort(Comparator.comparing(TradingAccount::getCode, Comparator.nullsLast(String::compareTo)));
        return all;
    }

    public TradingAccount create(String userId, AcctCreateReq req) {
        Sim sim = simRepo.require(req.getSimId(), ErrorCode.SIM_NOT_FOUND);
        if (sim.getState() != SimState.SETUP) {
            throw BizException.of(ErrorCode.SIM_STATE_ILLEGAL,
                    "模拟盘已进入「" + sim.getState().getLabel() + "」，只有筹备中才能创建交易账户");
        }
        SimConfig cfg = cfgOf(sim);

        long owned = accountRepo.countByUser(userId, sim.getId());
        if (owned >= cfg.getMaxAccountsPerUser()) {
            throw BizException.of(ErrorCode.ACCOUNT_LIMIT_EXCEEDED,
                    "每人在本模拟盘最多创建 " + cfg.getMaxAccountsPerUser() + " 个交易账户，当前已有 " + owned + " 个");
        }

        AccountParams params = req.getParams() == null ? new AccountParams() : req.getParams();
        validateParams(req.getMemberType(), params, cfg);
        applyParamDefaults(req.getMemberType(), params);

        TradingAccount a = new TradingAccount();
        a.setId(idGen.next(IdGen.ACCOUNT));
        a.setSimId(sim.getId());
        a.setUserId(userId);
        a.setCode(nextCode(sim.getId(), req.getMemberType()));
        a.setName(req.getName().trim());
        a.setMemberType(req.getMemberType());
        a.setSource(OrderSource.HUMAN);
        a.setParams(params);
        a.setFund(new FundInfo());
        a.setStat(new AccountStat());
        a.setCreatedAt(System.currentTimeMillis());
        save(a);
        log.info("用户 {} 在模拟盘 {} 创建交易账户 {} {}", userId, sim.getId(), a.getCode(), a.getName());
        return a;
    }

    public TradingAccount update(String accountId, String userId, AcctUpdateReq req) {
        TradingAccount a = requireOwned(accountId, userId);
        requireEditable(a);

        if (req.getName() != null && !req.getName().isBlank()) {
            a.setName(req.getName().trim());
        }
        if (req.getParams() != null) {
            SimConfig cfg = cfgOf(simRepo.require(a.getSimId(), ErrorCode.SIM_NOT_FOUND));
            validateParams(a.getMemberType(), req.getParams(), cfg);
            applyParamDefaults(a.getMemberType(), req.getParams());
            a.setParams(req.getParams());
        }
        save(a);
        return a;
    }

    public void delete(String accountId, String userId) {
        TradingAccount a = requireOwned(accountId, userId);
        requireEditable(a);
        purgeAccountData(a);
        log.info("用户 {} 删除交易账户 {}", userId, a.getCode());
    }

    /**
     * 开盘前的最后一道体检。参数是在 SETUP 期间陆续填的，规则配置也可能中途改过，
     * 所以注资之前必须按当下的 cfg 再校验一遍，避免带着残缺参数进入 RUNNING。
     */
    public void assertReadyForStart(TradingAccount a) {
        if (a.isParamsLocked()) {
            return;
        }
        Sim sim = simRepo.require(a.getSimId(), ErrorCode.SIM_NOT_FOUND);
        AccountParams p = a.getParams() == null ? new AccountParams() : a.getParams();
        try {
            validateParams(a.getMemberType(), p, cfgOf(sim));
        } catch (BizException e) {
            throw new BizException(e.getCode(),
                    "账户 " + a.getCode() + "（" + a.getName() + "）无法开盘：" + e.getMessage());
        }
        applyParamDefaults(a.getMemberType(), p);
        a.setParams(p);
    }

    /** 删除账户及其全部从属数据，模拟盘重置时也走这里。 */
    public void purgeAccountData(TradingAccount a) {
        for (String flowId : store.zRevRangeAll(RedisKeys.acctFlow(a.getId()))) {
            store.delete(RedisKeys.flow(flowId));
        }
        store.deleteByPattern(RedisKeys.P + "acct:" + a.getId() + ":*");
        store.zRem(RedisKeys.simRank(a.getSimId()), a.getId());
        accountRepo.removeAccount(a);
    }

    // ══════════════════════════ 视图组装 ══════════════════════════

    public AccountVO toVO(TradingAccount a) {
        AccountVO vo = AccountVO.of(a);
        if (vo == null) {
            return null;
        }
        Sim sim = simRepo.find(a.getSimId());
        if (sim != null) {
            vo.setSimName(sim.getName());
        }
        if (a.getUserId() != null) {
            User u = userRepo.find(a.getUserId());
            if (u != null) {
                vo.setUserName(u.getNickname() == null ? u.getUsername() : u.getNickname());
            }
        }
        return vo;
    }

    public List<AccountVO> toVOList(List<TradingAccount> accounts) {
        List<AccountVO> out = new ArrayList<>(accounts.size());
        // 同一批账户往往同属一个盘，缓存住 sim/user 避免 N+1 次 Redis 往返
        Map<String, Sim> simCache = new LinkedHashMap<>();
        Map<String, User> userCache = new LinkedHashMap<>();
        for (TradingAccount a : accounts) {
            AccountVO vo = AccountVO.of(a);
            Sim sim = simCache.computeIfAbsent(a.getSimId(), simRepo::find);
            if (sim != null) {
                vo.setSimName(sim.getName());
            }
            if (a.getUserId() != null) {
                User u = userCache.computeIfAbsent(a.getUserId(), userRepo::find);
                if (u != null) {
                    vo.setUserName(u.getNickname() == null ? u.getUsername() : u.getNickname());
                }
            }
            out.add(vo);
        }
        return out;
    }

    public List<Position> positions(String accountId) {
        List<Position> list = positionRepo.listByAccount(accountId);
        list.sort(Comparator.comparing(Position::getInstrumentKey, Comparator.nullsLast(String::compareTo)));
        return list;
    }

    public AcctSummaryVO summary(String accountId) {
        TradingAccount a = require(accountId);
        FundInfo f = a.getFund() == null ? new FundInfo() : a.getFund();
        AccountStat s = a.getStat() == null ? new AccountStat() : a.getStat();

        AcctSummaryVO vo = new AcctSummaryVO();
        vo.setAccountId(a.getId());
        vo.setCode(a.getCode());
        vo.setName(a.getName());
        vo.setMemberType(a.getMemberType());
        vo.setMemberTypeLabel(a.getMemberType() == null ? null : a.getMemberType().getLabel());

        vo.setAvailable(Nums.amount(f.getAvailable()));
        vo.setFrozen(Nums.amount(f.getFrozen()));
        vo.setMargin(Nums.amount(f.getMargin()));
        vo.setTotalDeposit(Nums.amount(f.getTotalDeposit()));
        vo.setFundTotal(Nums.amount(f.total()));
        vo.setEquity(Nums.amount(f.total() + s.getUnrealizedPnl()));

        double posMwh = 0;
        double posValue = 0;
        List<Position> positions = positionRepo.listByAccount(accountId);
        for (Position p : positions) {
            posMwh += Math.abs(p.getNetQty());
            double mark = p.getLastPrice() > 0 ? p.getLastPrice() : p.getAvgPrice();
            posValue += p.getNetQty() * mark;
        }
        vo.setPositionCount(positions.size());
        vo.setPositionMwh(Nums.qty(posMwh));
        vo.setPositionValue(Nums.amount(posValue));

        vo.setRealizedPnl(Nums.amount(s.getRealizedPnl()));
        vo.setUnrealizedPnl(Nums.amount(s.getUnrealizedPnl()));
        vo.setTotalPnl(Nums.amount(s.getTotalPnl()));
        vo.setTodayPnl(Nums.amount(s.getTodayPnl()));
        vo.setReturnPct(f.getTotalDeposit() <= 0 ? 0 : Nums.round(s.getTotalPnl() / f.getTotalDeposit(), 6));

        vo.setTradeCount(s.getTradeCount());
        vo.setBuyMwh(Nums.qty(s.getBuyMwh()));
        vo.setSellMwh(Nums.qty(s.getSellMwh()));
        vo.setAvgBuyPrice(Nums.price(s.getAvgBuyPrice()));
        vo.setAvgSellPrice(Nums.price(s.getAvgSellPrice()));
        vo.setFeePaid(Nums.amount(s.getFeePaid()));

        vo.setRank(simRepo.rankOf(a.getSimId(), a.getId()));
        vo.setTotalAccounts(store.sCard(RedisKeys.simAccounts(a.getSimId())));
        return vo;
    }

    // ══════════════════════════ 内部工具 ══════════════════════════

    /** 允许改动的前提：账户未锁定 且 模拟盘仍在 SETUP。两者任一不满足都拒绝。 */
    private void requireEditable(TradingAccount a) {
        if (a.isParamsLocked()) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED);
        }
        if (a.getSource() == OrderSource.ROBOT) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED, "机器人账户不可手工修改");
        }
        Sim sim = simRepo.require(a.getSimId(), ErrorCode.SIM_NOT_FOUND);
        if (sim.getState() != SimState.SETUP) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED,
                    "模拟盘已进入「" + sim.getState().getLabel() + "」，账户参数已锁定，开盘后不可修改");
        }
    }

    /**
     * 账户编号：主体前缀 + 4 位序号，序号按 (模拟盘, 主体类型) 独立自增。
     * 计数器不随重置回退，因此不会与历史编号撞车；仍保留存在性检查兜底。
     */
    private String nextCode(String simId, MemberType memberType) {
        String prefix = memberType.codePrefix();
        for (int i = 0; i < 50; i++) {
            long n = store.incr(RedisKeys.seq("acctcode:" + simId + ":" + prefix));
            String code = prefix + String.format("%04d", n);
            if (!accountRepo.codeExists(simId, code)) {
                return code;
            }
        }
        throw BizException.of(ErrorCode.ACCOUNT_CODE_EXISTS, "账户编号生成失败，请重试");
    }

    /** 按主体类型做分支校验，把所有缺失字段一次性告诉用户。 */
    private void validateParams(MemberType type, AccountParams p, SimConfig cfg) {
        if (type == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "市场主体类型不能为空");
        }
        double capital = p.getInitialCapital();
        if (capital < cfg.getInitialCapitalMin() || capital > cfg.getInitialCapitalMax()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, String.format(
                    "初始资金需在 %.0f ~ %.0f 元之间，当前填写 %.0f 元",
                    cfg.getInitialCapitalMin(), cfg.getInitialCapitalMax(), capital));
        }

        List<String> missing = new ArrayList<>();
        switch (type) {
            case GENCO -> {
                if (p.getGenType() == null) {
                    missing.add("机组类型 genType");
                }
                requirePositive(p.getInstalledCapacityMw(), "装机容量 installedCapacityMw(MW)", missing);
                requirePositive(p.getAnnualEnergyMwh(), "年度可发电量 annualEnergyMwh(MWh)", missing);
                requirePositive(p.getVariableCostPerMwh(), "变动成本 variableCostPerMwh(元/MWh)", missing);
            }
            case CONSUMER -> {
                requirePositive(p.getAnnualDemandMwh(), "年度用电需求 annualDemandMwh(MWh)", missing);
                requirePositive(p.getMaxAcceptPricePerMwh(), "可承受最高购电价 maxAcceptPricePerMwh(元/MWh)", missing);
                if (p.getLoadProfile() == null) {
                    missing.add("负荷特性 loadProfile");
                }
            }
            case RETAILER -> {
                requirePositive(p.getAgentDemandMwh(), "代理用户总电量 agentDemandMwh(MWh)", missing);
                requirePositive(p.getRetailPricePerMwh(), "零售套餐电价 retailPricePerMwh(元/MWh)", missing);
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCode.ACCOUNT_PARAM_MISSING,
                    type.getLabel() + "缺少必填参数：" + String.join("、", missing));
        }
    }

    private static void requirePositive(Double v, String label, List<String> missing) {
        if (v == null || v <= 0) {
            missing.add(label);
        }
    }

    /**
     * 补齐通用风控字段。BE-2 的下单风控会直接读 maxSingleOrderMwh / maxPositionMwh，
     * 这里按主体规模给出兜底值，避免用户没填时风控拿到 null。
     */
    private void applyParamDefaults(MemberType type, AccountParams p) {
        double scale = switch (type) {
            case GENCO -> nz(p.getAnnualEnergyMwh());
            case CONSUMER -> nz(p.getAnnualDemandMwh());
            case RETAILER -> nz(p.getAgentDemandMwh());
        };
        if (scale <= 0) {
            scale = 1_000_000;
        }
        if (p.getMaxSingleOrderMwh() == null || p.getMaxSingleOrderMwh() <= 0) {
            p.setMaxSingleOrderMwh(Nums.qty(scale * 0.05));
        }
        if (p.getMaxPositionMwh() == null || p.getMaxPositionMwh() <= 0) {
            p.setMaxPositionMwh(Nums.qty(scale));
        }
        if (p.getRiskAppetite() == null || p.getRiskAppetite() < 1 || p.getRiskAppetite() > 5) {
            p.setRiskAppetite(3);
        }
        if (type == MemberType.GENCO) {
            if (p.getMinOutputRatio() == null) {
                p.setMinOutputRatio(0.4);
            }
            if (p.getMaxOutputRatio() == null) {
                p.setMaxOutputRatio(1.0);
            }
            if (p.getForcedOutageRate() == null) {
                p.setForcedOutageRate(0.03);
            }
        }
        if (type == MemberType.CONSUMER && p.getRetailBenchmarkPrice() == null) {
            p.setRetailBenchmarkPrice(Nums.price(nz(p.getMaxAcceptPricePerMwh()) * 1.05));
        }
        if (type == MemberType.RETAILER && p.getAgentUserCount() == null) {
            p.setAgentUserCount(Math.max(1, (int) (scale / 2000)));
        }
    }

    /** 机器人账户的参数由主体类型与策略推导，保证成本线与报价逻辑自洽。 */
    private AccountParams robotParams(MemberType type, RobotStrategy strategy, double capital, SimConfig cfg) {
        AccountParams p = new AccountParams();
        p.setInitialCapital(capital);
        double base = cfg.getBenchmarkPrice() <= 0 ? 380 : cfg.getBenchmarkPrice();

        switch (type) {
            case GENCO -> {
                GenType gt = genTypeOf(strategy);
                double capacityMw = switch (gt) {
                    case COAL -> 660;
                    case GAS -> 390;
                    case HYDRO -> 300;
                    case WIND -> 200;
                    case SOLAR -> 150;
                    case NUCLEAR -> 1000;
                    case STORAGE -> 100;
                };
                double utilHours = switch (gt) {
                    case COAL -> 4500;
                    case GAS -> 2500;
                    case HYDRO -> 3500;
                    case WIND -> 2200;
                    case SOLAR -> 1300;
                    case NUCLEAR -> 7500;
                    case STORAGE -> 1200;
                };
                p.setGenType(gt);
                p.setInstalledCapacityMw(capacityMw);
                p.setAnnualEnergyMwh(Nums.qty(capacityMw * utilHours));
                p.setVariableCostPerMwh(Nums.price(gt.getTypicalCost()));
            }
            case CONSUMER -> {
                p.setAnnualDemandMwh(900_000d);
                p.setMaxAcceptPricePerMwh(Nums.price(base * 1.18));
                p.setLoadProfile(loadProfileOf(strategy));
                p.setRetailBenchmarkPrice(Nums.price(base * 1.28));
            }
            case RETAILER -> {
                p.setAgentDemandMwh(1_200_000d);
                p.setAgentUserCount(600);
                p.setRetailPricePerMwh(Nums.price(base * 1.14));
            }
        }
        applyParamDefaults(type, p);
        // 激进策略允许更大的单笔手数，让盘口有层次
        p.setRiskAppetite(switch (strategy == null ? RobotStrategy.NOISE : strategy) {
            case MARKET_MAKER -> 3;
            case TREND, ARBITRAGE -> 4;
            case COST_BASED -> 2;
            case NOISE -> 5;
            case DEMAND -> 3;
        });
        return p;
    }

    private static GenType genTypeOf(RobotStrategy s) {
        return switch (s == null ? RobotStrategy.COST_BASED : s) {
            case COST_BASED -> GenType.COAL;
            case MARKET_MAKER -> GenType.GAS;
            case TREND -> GenType.HYDRO;
            case NOISE -> GenType.WIND;
            case ARBITRAGE -> GenType.SOLAR;
            case DEMAND -> GenType.NUCLEAR;
        };
    }

    private static LoadProfile loadProfileOf(RobotStrategy s) {
        return switch (s == null ? RobotStrategy.DEMAND : s) {
            case DEMAND, COST_BASED -> LoadProfile.CONTINUOUS;
            case MARKET_MAKER -> LoadProfile.THREE_SHIFT;
            case TREND -> LoadProfile.TWO_SHIFT;
            case ARBITRAGE -> LoadProfile.COMMERCIAL;
            case NOISE -> LoadProfile.RESIDENTIAL;
        };
    }

    private static SimConfig cfgOf(Sim sim) {
        return sim.getCfg() == null ? new SimConfig() : sim.getCfg();
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
