package com.github.zhizi01.ets.service.clearing;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.ArchivePort;
import com.github.zhizi01.ets.api.AuditPort;
import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.ContractStatus;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.BillRepo;
import com.github.zhizi01.ets.repo.ContractRepo;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.service.sim.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 周期结算：为模拟盘内每个账户出一张 Bill。
 * <p>
 * 偏差考核口径：实际交割电量在合同量上叠加 ±3% 随机偏差（按合同 id 播种，
 * 保证同一周期重复结算得到相同结果），超出 deviationTolerancePct 的部分按基准电价 1.5 倍罚款。
 * 重复结算会覆盖旧账单但不会重复扣款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** 交割偏差模拟幅度 */
    private static final double DEVIATION_RANGE = 0.03;
    /** 偏差考核罚金相对基准电价的倍数 */
    private static final double PENALTY_MULTIPLIER = 1.5;
    public static final String PERIOD_FINAL = "FINAL";

    private final ContractRepo contractRepo;
    private final BillRepo billRepo;
    private final SimRepo simRepo;
    private final IdGen idGen;

    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private FundPort fundPort;
    @Lazy
    @Autowired
    private SimPort simPort;
    @Lazy
    @Autowired
    private WsPort wsPort;
    @Lazy
    @Autowired
    private AuditPort auditPort;
    @Lazy
    @Autowired
    private ArchivePort archivePort;
    @Lazy
    @Autowired
    private TradingSessionService sessionService;

    public List<Bill> settle(String simId, String period) {
        Sim sim = simPort.requireSim(simId);
        String p = (period == null || period.isBlank()) ? PERIOD_FINAL : period.trim();

        List<TradingAccount> accounts = accountPort.listBySim(simId);
        if (accounts == null || accounts.isEmpty()) {
            throw BizException.of(ErrorCode.SETTLE_FAILED, "该模拟盘下没有交易账户，无法结算");
        }

        List<Bill> bills = new ArrayList<>(accounts.size());
        List<String> failed = new ArrayList<>();
        for (TradingAccount acct : accounts) {
            try {
                bills.add(billOf(sim, acct, p));
            } catch (Exception e) {
                log.error("账户结算失败 acct={} period={}", acct.getId(), p, e);
                failed.add((acct.getCode() == null ? acct.getId() : acct.getCode()) + "：" + e.getMessage());
            }
        }
        if (bills.isEmpty()) {
            throw BizException.of(ErrorCode.SETTLE_FAILED,
                    "全部账户结算失败" + (failed.isEmpty() ? "" : "：" + String.join("；", failed)));
        }

        // 排名按本期利润，与排行榜的 totalPnl 口径分开：账单看单期表现，排行榜看累计。
        bills.sort(Comparator.comparingDouble(Bill::getProfit).reversed());
        int rank = 1;
        for (Bill b : bills) {
            b.setRank(rank++);
            billRepo.saveBill(b);
            archivePort.offerBill(b);
        }
        for (TradingAccount acct : accounts) {
            simRepo.updateRank(simId, acct.getId(), acct.getStat() == null ? 0 : acct.getStat().getTotalPnl());
        }
        int settledSessions = sessionService.markClosedAsSettled(simId);

        safePush("sim." + simId, "结算完成 " + p);
        safeAudit(simId, "SETTLE", p, "结算完成，出具账单 " + bills.size() + " 份，场次结算 " + settledSessions);
        log.info("结算完成 sim={} period={} bills={} failed={} settledSessions={}",
                simId, p, bills.size(), failed.size(), settledSessions);
        if (!failed.isEmpty()) {
            throw BizException.of(ErrorCode.SETTLE_FAILED,
                    "已出具 " + bills.size() + " 份账单，" + failed.size() + " 个账户失败：" + String.join("；", failed));
        }
        return bills;
    }

    // ── 单账户结算 ──────────────────────────────────────────

    private Bill billOf(Sim sim, TradingAccount acct, String period) {
        Bill bill = new Bill();
        Bill old = existingBill(acct.getId(), period);
        bill.setId(old != null ? old.getId() : idGen.next(IdGen.BILL));
        bill.setSimId(sim.getId());
        bill.setAccountId(acct.getId());
        bill.setAccountCode(acct.getCode());
        bill.setAccountName(acct.getName());
        bill.setPeriod(period);
        bill.setSettledAt(System.currentTimeMillis());

        double feeRateBuy = sim.getCfg().getFeeRateBuy();
        double feeRateSell = sim.getCfg().getFeeRateSell();

        double buyQty = 0, buyAmount = 0, sellQty = 0, sellAmount = 0;
        double spotBuyQty = 0, spotBuyAmount = 0, spotSellQty = 0, spotSellAmount = 0;
        double actualQty = 0, fee = 0;

        for (Contract c : contractRepo.listByAccount(acct.getId())) {
            if (!inPeriod(c, period)) {
                continue;
            }
            boolean asBuyer = acct.getId().equals(c.getBuyAccountId());
            boolean spot = c.getVariety() == Variety.SPOT_DA;
            fee += Nums.amount(c.getAmount() * (asBuyer ? feeRateBuy : feeRateSell));

            if (spot) {
                if (asBuyer) {
                    spotBuyQty += c.getQuantity();
                    spotBuyAmount += c.getAmount();
                } else {
                    spotSellQty += c.getQuantity();
                    spotSellAmount += c.getAmount();
                }
                continue;
            }

            if (asBuyer) {
                buyQty += c.getQuantity();
                buyAmount += c.getAmount();
            } else {
                sellQty += c.getQuantity();
                sellAmount += c.getAmount();
            }
            actualQty += markDelivered(c);
        }

        double contractQty = Nums.qty(buyQty + sellQty);
        double contractAmount = Nums.amount(buyAmount + sellAmount);
        actualQty = Nums.qty(actualQty);
        double deviationQty = Nums.qty(actualQty - contractQty);
        double tolerance = Math.abs(contractQty) * sim.getCfg().getDeviationTolerancePct();
        double excess = Math.max(0, Math.abs(deviationQty) - tolerance);
        double penalty = Nums.amount(excess * sim.getCfg().getBenchmarkPrice() * PENALTY_MULTIPLIER);

        bill.setContractQty(contractQty);
        bill.setContractAmount(contractAmount);
        bill.setActualQty(actualQty);
        bill.setDeviationQty(deviationQty);
        bill.setDeviationPenalty(penalty);
        bill.setSpotQty(Nums.qty(spotBuyQty + spotSellQty));
        bill.setSpotAmount(Nums.amount(spotBuyAmount + spotSellAmount));
        bill.setFeeTotal(Nums.amount(fee));

        double totalSellQty = sellQty + spotSellQty;
        double totalBuyQty = buyQty + spotBuyQty;
        double revenue = sellAmount + spotSellAmount;
        double cost = buyAmount + spotBuyAmount + bill.getFeeTotal() + penalty;

        AccountParams pa = acct.getParams();
        MemberType mt = acct.getMemberType();
        if (mt == MemberType.GENCO) {
            // 发电侧：售电收入减去自身变动成本才是真实盈利
            cost += Nums.amount(totalSellQty * val(pa == null ? null : pa.getVariableCostPerMwh(), 0));
        } else if (mt == MemberType.CONSUMER) {
            // 用电侧：以目录电价（缺失时退化为可承受最高价 / 基准电价）折算购电价值
            double benchmark = val(pa == null ? null : pa.getRetailBenchmarkPrice(),
                    val(pa == null ? null : pa.getMaxAcceptPricePerMwh(), sim.getCfg().getBenchmarkPrice()));
            revenue += Nums.amount(totalBuyQty * benchmark);
        } else if (mt == MemberType.RETAILER) {
            double retail = val(pa == null ? null : pa.getRetailPricePerMwh(), sim.getCfg().getBenchmarkPrice());
            revenue += Nums.amount(totalBuyQty * retail);
        }

        bill.setRevenue(Nums.amount(revenue));
        bill.setCost(Nums.amount(cost));
        bill.setProfit(Nums.amount(revenue - cost));

        // 罚金只在首次出账时真正扣款，避免重复结算把账户罚穿
        if (old == null && Nums.isPositive(penalty)) {
            try {
                fundPort.adjust(acct.getId(), -penalty, "偏差考核 " + period);
            } catch (Exception e) {
                log.warn("偏差考核扣款失败 acct={}: {}", acct.getId(), e.getMessage());
            }
        }
        return bill;
    }

    /** 交割量 = 合同量 × (1 ± 3%)，随机数按合同 id 播种以保证可复现。 */
    private double markDelivered(Contract c) {
        Random rnd = new Random(c.getId() == null ? 0 : c.getId().hashCode());
        double delivered = Nums.qty(c.getQuantity() * (1 + (rnd.nextDouble() * 2 - 1) * DEVIATION_RANGE));
        if (c.getDeliveredQty() <= 0 || c.getStatus() != ContractStatus.DELIVERED) {
            c.setDeliveredQty(delivered);
            c.setStatus(ContractStatus.DELIVERED);
            contractRepo.saveContract(c);
        }
        return c.getDeliveredQty();
    }

    private Bill existingBill(String accountId, String period) {
        for (Bill b : billRepo.listByAccount(accountId)) {
            if (period.equals(b.getPeriod())) {
                return b;
            }
        }
        return null;
    }

    /**
     * 合同是否落在结算周期内。period=FINAL 时全量；
     * 否则按交割区间与 [period-01, period-31] 是否有重叠判断（yyyy-MM-dd 字典序即时间序）。
     */
    private boolean inPeriod(Contract c, String period) {
        if (PERIOD_FINAL.equalsIgnoreCase(period)) {
            return true;
        }
        String from = period + "-01";
        String to = period + "-31";
        String ds = c.getDeliveryStart();
        String de = c.getDeliveryEnd();
        if (ds == null && de == null) {
            return false;
        }
        String start = ds == null ? de : ds;
        String end = de == null ? ds : de;
        return start.compareTo(to) <= 0 && end.compareTo(from) >= 0;
    }

    private double val(Double v, double dft) {
        return v == null ? dft : v;
    }

    private void safePush(String topic, Object data) {
        try {
            wsPort.push(topic, data);
        } catch (Exception ignored) {
            // 推送失败不影响结算主流程
        }
    }

    private void safeAudit(String simId, String action, String target, String detail) {
        try {
            auditPort.system(simId, action, target, detail);
        } catch (Exception ignored) {
            // 审计失败不影响结算主流程
        }
    }
}
