package com.github.zhizi01.ets.service.account;

import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.FlowType;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.AccountStat;
import com.github.zhizi01.ets.domain.model.FundFlow;
import com.github.zhizi01.ets.domain.model.FundInfo;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.repo.FundFlowRepo;
import com.github.zhizi01.ets.repo.PositionRepo;
import com.github.zhizi01.ets.repo.SimRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 资金、持仓与盈亏。撮合引擎会高频调用本类，因此有两条硬约束：
 * <ol>
 *   <li>同一账户的余额变更必须串行 —— 用账户级 {@link ReentrantLock} 分段锁；</li>
 *   <li>一笔成交涉及买卖双方两把锁，一律按 accountId 字典序依次获取，杜绝交叉死锁。</li>
 * </ol>
 * 锁内必须重新从 Redis 读取账户，不能沿用调用方传入的旧快照。
 */
@Slf4j
@Service
public class FundServiceImpl implements FundPort {

    private final AccountRepo accountRepo;
    private final PositionRepo positionRepo;
    private final FundFlowRepo fundFlowRepo;
    private final SimRepo simRepo;
    private final IdGen idGen;
    private final MarketPort marketPort;

    /** 账户级锁。数量与账户数同阶，随演练规模自然收敛，无需清理。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FundServiceImpl(AccountRepo accountRepo,
                           PositionRepo positionRepo,
                           FundFlowRepo fundFlowRepo,
                           SimRepo simRepo,
                           IdGen idGen,
                           @Lazy MarketPort marketPort) {
        this.accountRepo = accountRepo;
        this.positionRepo = positionRepo;
        this.fundFlowRepo = fundFlowRepo;
        this.simRepo = simRepo;
        this.idGen = idGen;
        this.marketPort = marketPort;
    }

    // ══════════════════════════ 注资与调整 ══════════════════════════

    @Override
    public void deposit(String accountId, double amount, String remark) {
        double amt = Nums.amount(amount);
        if (amt <= 0) {
            return;
        }
        locked(accountId, () -> {
            TradingAccount a = require(accountId);
            FundInfo f = fundOf(a);
            f.setAvailable(Nums.amount(f.getAvailable() + amt));
            f.setTotalDeposit(Nums.amount(f.getTotalDeposit() + amt));
            persist(a);
            writeFlow(a, FlowType.DEPOSIT, amt, null, remark == null ? "初始注资" : remark);
            return null;
        });
    }

    @Override
    public void adjust(String accountId, double amount, String remark) {
        double amt = Nums.amount(amount);
        if (amt == 0) {
            return;
        }
        locked(accountId, () -> {
            TradingAccount a = require(accountId);
            FundInfo f = fundOf(a);
            if (amt < 0 && f.getAvailable() + amt < 0) {
                throw BizException.of(ErrorCode.INSUFFICIENT_FUND,
                        String.format("可用资金仅 %.2f 元，无法扣减 %.2f 元", f.getAvailable(), -amt));
            }
            f.setAvailable(Nums.amount(f.getAvailable() + amt));
            // 调整额计入累计注资，收益率的分母才不会失真
            f.setTotalDeposit(Nums.amount(Math.max(0, f.getTotalDeposit() + amt)));
            persist(a);
            writeFlow(a, FlowType.ADJUST, amt, null, remark == null ? "管理员调整" : remark);
            return null;
        });
    }

    // ══════════════════════════ 冻结与释放 ══════════════════════════

    @Override
    public boolean freezeForBuy(String accountId, double amount, String refId) {
        return freeze(accountId, amount, refId, true);
    }

    @Override
    public boolean freezeMargin(String accountId, double amount, String refId) {
        return freeze(accountId, amount, refId, false);
    }

    @Override
    public void unfreezeBuy(String accountId, double amount, String refId) {
        unfreeze(accountId, amount, refId, true);
    }

    @Override
    public void releaseMargin(String accountId, double amount, String refId) {
        unfreeze(accountId, amount, refId, false);
    }

    private boolean freeze(String accountId, double amount, String refId, boolean buySide) {
        double amt = Nums.amount(amount);
        if (amt <= 0) {
            return true;
        }
        Boolean ok = locked(accountId, () -> {
            TradingAccount a = accountRepo.find(accountId);
            if (a == null) {
                return false;
            }
            FundInfo f = fundOf(a);
            if (!Nums.gte(f.getAvailable(), amt)) {
                return false;
            }
            f.setAvailable(Nums.amount(f.getAvailable() - amt));
            if (buySide) {
                f.setFrozen(Nums.amount(f.getFrozen() + amt));
            } else {
                f.setMargin(Nums.amount(f.getMargin() + amt));
            }
            persist(a);
            writeFlow(a, FlowType.FREEZE, -amt, refId, buySide ? "买单冻结货款" : "卖单冻结保证金");
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** 释放金额一律夹在当前冻结额之内，防止重复调用把冻结做成负数或凭空生钱。 */
    private void unfreeze(String accountId, double amount, String refId, boolean buySide) {
        double amt = Nums.amount(amount);
        if (amt <= 0) {
            return;
        }
        locked(accountId, () -> {
            TradingAccount a = accountRepo.find(accountId);
            if (a == null) {
                return null;
            }
            FundInfo f = fundOf(a);
            double pool = buySide ? f.getFrozen() : f.getMargin();
            double back = Math.min(pool, amt);
            if (back <= 0) {
                return null;
            }
            if (buySide) {
                f.setFrozen(Nums.amount(f.getFrozen() - back));
            } else {
                f.setMargin(Nums.amount(f.getMargin() - back));
            }
            f.setAvailable(Nums.amount(f.getAvailable() + back));
            persist(a);
            writeFlow(a, FlowType.UNFREEZE, back, refId, buySide ? "解冻货款" : "释放保证金");
            return null;
        });
    }

    // ══════════════════════════ 成交结算 ══════════════════════════

    @Override
    public void applyTrade(Trade trade) {
        if (trade == null || trade.getBuyAccountId() == null || trade.getSellAccountId() == null) {
            return;
        }
        // 持仓按 instrumentKey 归集，缺 variety 就无法落仓。必须在动钱之前拦下，
        // 否则会出现「买方已扣款、卖方未结算」的半成品状态。
        if (trade.getVariety() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "成交 " + trade.getId() + " 缺少 variety，无法确定标的，已拒绝结算");
        }
        locked2(trade.getBuyAccountId(), trade.getSellAccountId(), () -> {
            SimConfig cfg = cfgOf(trade.getSimId());
            settleSide(trade, Side.BUY, cfg);
            settleSide(trade, Side.SELL, cfg);
        });
    }

    private void settleSide(Trade t, Side side, SimConfig cfg) {
        String accountId = side == Side.BUY ? t.getBuyAccountId() : t.getSellAccountId();
        TradingAccount a = accountRepo.find(accountId);
        if (a == null) {
            log.warn("成交 {} 的{}方账户 {} 不存在，跳过结算", t.getId(), side.getLabel(), accountId);
            return;
        }
        double qty = Nums.qty(t.getQuantity());
        double price = Nums.price(t.getPrice());
        double amount = Nums.amount(t.getAmount() > 0 ? t.getAmount() : price * qty);
        double fee = Nums.amount(side == Side.BUY ? t.getBuyFee() : t.getSellFee());
        FundInfo f = fundOf(a);

        // 订单级预留（买方货款 / 卖方保证金）由撮合侧在调用本方法之前释放回可用资金，
        // 这里只按可用资金收付。绝不能再去动 frozen / margin —— 那两个池子里剩下的
        // 是该账户其它挂单的钱，挪用会让可用资金虚高、冻结虚低。
        if (side == Side.BUY) {
            double cost = Nums.amount(amount + fee);
            if (!Nums.gte(f.getAvailable(), cost)) {
                log.warn("账户 {} 结算成交 {} 时可用资金不足，应付 {} 实有 {}",
                        a.getCode(), t.getId(), cost, f.getAvailable());
            }
            f.setAvailable(Nums.amount(Math.max(0, f.getAvailable() - cost)));
        } else {
            f.setAvailable(Nums.amount(f.getAvailable() + amount - fee));
        }

        double pnl = realizedPnlOf(a, side, price, qty, fee, cfg);
        updatePosition(a, t, side, price, qty, amount, pnl);
        updateStat(a, side, price, qty, amount, fee, pnl);
        persist(a);

        writeFlow(a, side == Side.BUY ? FlowType.TRADE_PAY : FlowType.TRADE_RECV,
                side == Side.BUY ? -amount : amount, t.getId(),
                String.format("%s %s %.3f MWh @ %.2f 元/MWh",
                        t.instrumentKey(), side.getLabel(), qty, price));
        if (fee > 0) {
            writeFlow(a, FlowType.FEE, -fee, t.getId(), "成交手续费");
        }
        refreshRank(a);
    }

    /**
     * 已实现盈亏口径（DESIGN.md 7.7）：
     * 发电方 {@code (成交价 - 变动成本) × 电量}、用电方 {@code (可承受价 - 成交价) × 电量}、
     * 售电公司 {@code (零售价 - 成交价) × 电量}，再扣手续费。
     * 三者各有一个「主方向」（发电方卖、用电方与售电公司买），
     * 反向成交时收益符号取反 —— 低于自身成本买入同样是赚。
     */
    public double realizedPnlOf(TradingAccount a, Side side, double price, double qty, double fee, SimConfig cfg) {
        AccountParams p = a.getParams() == null ? new AccountParams() : a.getParams();
        MemberType type = a.getMemberType() == null ? MemberType.CONSUMER : a.getMemberType();
        double benchmark = cfg == null || cfg.getBenchmarkPrice() <= 0 ? 380 : cfg.getBenchmarkPrice();

        double unit;
        boolean primaryIsSell;
        switch (type) {
            case GENCO -> {
                double cost = p.getVariableCostPerMwh() != null ? p.getVariableCostPerMwh()
                        : (p.getGenType() != null ? p.getGenType().getTypicalCost() : benchmark * 0.85);
                unit = price - cost;
                primaryIsSell = true;
            }
            case CONSUMER -> {
                double accept = p.getMaxAcceptPricePerMwh() != null ? p.getMaxAcceptPricePerMwh() : benchmark * 1.15;
                unit = accept - price;
                primaryIsSell = false;
            }
            default -> {
                double retail = p.getRetailPricePerMwh() != null ? p.getRetailPricePerMwh() : benchmark * 1.12;
                unit = retail - price;
                primaryIsSell = false;
            }
        }
        boolean primary = primaryIsSell ? side == Side.SELL : side == Side.BUY;
        return Nums.amount((primary ? unit : -unit) * qty - fee);
    }

    public double realizedPnlOf(TradingAccount a, Side side, double price, double qty, double fee) {
        return realizedPnlOf(a, side, price, qty, fee, cfgOf(a.getSimId()));
    }

    private void updatePosition(TradingAccount a, Trade t, Side side,
                                double price, double qty, double amount, double pnl) {
        Position p = positionRepo.findOrCreate(a.getId(), t.instrumentKey());
        if (side == Side.BUY) {
            p.setBuyQty(Nums.qty(p.getBuyQty() + qty));
            p.setBuyAmount(Nums.amount(p.getBuyAmount() + amount));
        } else {
            p.setSellQty(Nums.qty(p.getSellQty() + qty));
            p.setSellAmount(Nums.amount(p.getSellAmount() + amount));
        }
        p.setNetQty(Nums.qty(p.getBuyQty() - p.getSellQty()));
        // 净多头看买入均价、净空头看卖出均价，持平则无成本可言
        if (Nums.isPositive(p.getNetQty())) {
            p.setAvgPrice(Nums.price(p.avgBuyPrice()));
        } else if (Nums.isPositive(-p.getNetQty())) {
            p.setAvgPrice(Nums.price(p.avgSellPrice()));
        } else {
            p.setAvgPrice(0);
        }
        p.setLastPrice(price);
        p.setRealizedPnl(Nums.amount(p.getRealizedPnl() + pnl));
        p.setUnrealizedPnl(Nums.amount(p.getNetQty() * (price - p.getAvgPrice())));
        positionRepo.save(p);
    }

    private void updateStat(TradingAccount a, Side side, double price, double qty,
                            double amount, double fee, double pnl) {
        AccountStat s = statOf(a);
        s.setTradeCount(s.getTradeCount() + 1);
        if (side == Side.BUY) {
            s.setBuyMwh(Nums.qty(s.getBuyMwh() + qty));
            s.setBuyAmount(Nums.amount(s.getBuyAmount() + amount));
            s.setAvgBuyPrice(Nums.price(Nums.div(s.getBuyAmount(), s.getBuyMwh())));
        } else {
            s.setSellMwh(Nums.qty(s.getSellMwh() + qty));
            s.setSellAmount(Nums.amount(s.getSellAmount() + amount));
            s.setAvgSellPrice(Nums.price(Nums.div(s.getSellAmount(), s.getSellMwh())));
        }
        s.setFeePaid(Nums.amount(s.getFeePaid() + fee));
        s.setRealizedPnl(Nums.amount(s.getRealizedPnl() + pnl));
        s.setTodayPnl(Nums.amount(s.getTodayPnl() + pnl));
        s.setUnrealizedPnl(Nums.amount(sumUnrealized(a.getId(), price)));
        s.setTotalPnl(Nums.amount(s.getRealizedPnl() + s.getUnrealizedPnl()));
    }

    // ══════════════════════════ 盯市 ══════════════════════════

    @Override
    public void markToMarket(String accountId) {
        locked(accountId, () -> {
            TradingAccount a = accountRepo.find(accountId);
            if (a == null) {
                return null;
            }
            double unrealized = 0;
            for (Position p : positionRepo.listByAccount(accountId)) {
                double mark = referencePrice(a.getSimId(), p.getInstrumentKey(), p.getLastPrice());
                if (mark > 0) {
                    p.setLastPrice(Nums.price(mark));
                }
                p.setUnrealizedPnl(Nums.amount(p.getNetQty() * (p.getLastPrice() - p.getAvgPrice())));
                positionRepo.save(p);
                unrealized += p.getUnrealizedPnl();
            }
            AccountStat s = statOf(a);
            s.setUnrealizedPnl(Nums.amount(unrealized));
            s.setTotalPnl(Nums.amount(s.getRealizedPnl() + s.getUnrealizedPnl()));
            persist(a);
            refreshRank(a);
            return null;
        });
    }

    /** 结算过程中不重新查行情，直接用本笔成交价对同标的持仓做近似盯市。 */
    private double sumUnrealized(String accountId, double lastPrice) {
        double sum = 0;
        for (Position p : positionRepo.listByAccount(accountId)) {
            double mark = p.getLastPrice() > 0 ? p.getLastPrice() : lastPrice;
            sum += p.getNetQty() * (mark - p.getAvgPrice());
        }
        return sum;
    }

    private double referencePrice(String simId, String instrumentKey, double fallback) {
        try {
            double v = marketPort.referencePrice(simId, instrumentKey);
            return v > 0 ? v : fallback;
        } catch (Exception e) {
            // 行情模块尚未就绪或标的还没有成交，退回持仓自带的最新价
            return fallback;
        }
    }

    // ══════════════════════════ 内部工具 ══════════════════════════

    private void refreshRank(TradingAccount a) {
        AccountStat s = statOf(a);
        simRepo.updateRank(a.getSimId(), a.getId(), s.getTotalPnl());
        s.setRank((int) simRepo.rankOf(a.getSimId(), a.getId()));
    }

    private void writeFlow(TradingAccount a, FlowType type, double amount, String refId, String remark) {
        FundFlow flow = new FundFlow();
        flow.setId(idGen.next(IdGen.FLOW));
        flow.setSimId(a.getSimId());
        flow.setAccountId(a.getId());
        flow.setType(type);
        flow.setAmount(Nums.amount(amount));
        flow.setBalanceAfter(Nums.amount(fundOf(a).getAvailable()));
        flow.setRefId(refId);
        flow.setRemark(remark);
        flow.setCreatedAt(System.currentTimeMillis());
        fundFlowRepo.saveFlow(flow);
    }

    private TradingAccount require(String accountId) {
        return accountRepo.require(accountId, ErrorCode.ACCOUNT_NOT_FOUND);
    }

    private void persist(TradingAccount a) {
        a.setUpdatedAt(System.currentTimeMillis());
        accountRepo.saveAccount(a);
    }

    private SimConfig cfgOf(String simId) {
        Sim sim = simId == null ? null : simRepo.find(simId);
        return sim == null || sim.getCfg() == null ? new SimConfig() : sim.getCfg();
    }

    private static FundInfo fundOf(TradingAccount a) {
        if (a.getFund() == null) {
            a.setFund(new FundInfo());
        }
        return a.getFund();
    }

    private static AccountStat statOf(TradingAccount a) {
        if (a.getStat() == null) {
            a.setStat(new AccountStat());
        }
        return a.getStat();
    }

    // ── 分段锁 ──────────────────────────────────────────────

    private ReentrantLock lockOf(String accountId) {
        return locks.computeIfAbsent(accountId, k -> new ReentrantLock());
    }

    private <T> T locked(String accountId, Supplier<T> action) {
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 两个账户按 id 字典序依次加锁，保证任意两笔成交的加锁顺序一致。 */
    private void locked2(String accountA, String accountB, Runnable action) {
        if (accountA.equals(accountB)) {
            locked(accountA, () -> {
                action.run();
                return null;
            });
            return;
        }
        boolean aFirst = accountA.compareTo(accountB) < 0;
        ReentrantLock first = lockOf(aFirst ? accountA : accountB);
        ReentrantLock second = lockOf(aFirst ? accountB : accountA);
        first.lock();
        try {
            second.lock();
            try {
                action.run();
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    /** 供组合分析复用的只读工具：把一批成交按主体口径换算成逐笔盈亏。 */
    public List<Position> positionsOf(String accountId) {
        return positionRepo.listByAccount(accountId);
    }
}
