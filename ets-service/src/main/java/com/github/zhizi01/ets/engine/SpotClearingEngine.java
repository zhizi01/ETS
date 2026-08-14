package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.AuditPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SpotBidStatus;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.SpotBidRepo;
import com.github.zhizi01.ets.service.clearing.ContractService;
import com.github.zhizi01.ets.service.trade.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 日前现货出清：24 个小时段彼此独立，各自求供需曲线交点得到该小时的统一出清价与出清电量。
 * 无交点的小时段记 price=0 / volume=0。出清价受 priceFloor / priceCap 约束。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpotClearingEngine {

    public static final int HOURS = 24;

    private final SpotBidRepo spotBidRepo;
    private final RiskService riskService;
    private final ContractService contractService;

    @Lazy
    @Autowired
    private SimPort simPort;
    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private WsPort wsPort;
    @Lazy
    @Autowired
    private AuditPort auditPort;

    /** 一条报价曲线在某个小时上的分支，alloc 为该小时分到的中标电量。 */
    private static final class Leg {
        private final SpotBid bid;
        private final SpotSegment seg;
        private final double price;
        private final double quantity;
        private double alloc;

        Leg(SpotBid bid, SpotSegment seg) {
            this.bid = bid;
            this.seg = seg;
            this.price = seg.getPrice();
            this.quantity = seg.getQuantity();
        }
    }

    public void runSpotClearing(String sessionId) {
        TradingSession sess = simPort.requireSession(sessionId);
        Sim sim = simPort.requireSim(sess.getSimId());
        if (sess.getState() == TSessionState.CLOSED || sess.getState() == TSessionState.SETTLED) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次已" + sess.getState().getLabel() + "，不能再次出清");
        }
        List<SpotBid> bids = new ArrayList<>();
        for (SpotBid b : spotBidRepo.listBySession(sessionId)) {
            if (b.getStatus() == SpotBidStatus.ACTIVE) {
                bids.add(b);
            }
        }
        Map<String, TradingAccount> accounts = new HashMap<>();
        for (SpotBid b : bids) {
            accounts.computeIfAbsent(b.getAccountId(), accountPort::require);
        }

        double totalVolume = 0;
        double totalAmount = 0;
        for (int hour = 0; hour < HOURS; hour++) {
            double[] hr = clearHour(hour, bids, accounts, sess, sim);
            spotBidRepo.saveClearing(sessionId, hour, hr[0], hr[1]);
            totalVolume += hr[1];
            totalAmount += hr[0] * hr[1];
        }

        for (SpotBid b : bids) {
            double qty = 0;
            double amount = 0;
            for (SpotSegment s : b.getSegments()) {
                qty += s.getClearedQty();
                amount += s.getClearedQty() * s.getClearedPrice();
            }
            b.setClearedQty(Nums.qty(qty));
            b.setClearedAmount(Nums.amount(amount));
            b.setStatus(SpotBidStatus.CLEARED);
            spotBidRepo.update(b);
            safePush("order." + b.getAccountId(), b);
        }

        // 场次层面的「出清价」取全日成交量加权均价，便于行情与排行统一口径
        double vwap = Nums.price(Nums.div(totalAmount, totalVolume));
        if (Nums.isPositive(vwap)) {
            saveClearPrice(sessionId, vwap);
        }
        safeAudit(sess.getSimId(), "SPOT_CLEARING", sessionId,
                "24 段出清完成，全日成交 " + Nums.qty(totalVolume) + " MWh，加权均价 " + vwap + " 元/MWh");
        log.info("现货出清完成 session={} volume={} vwap={}", sessionId, Nums.qty(totalVolume), vwap);
    }

    /**
     * 24 段出清期间每笔成交都会让 MarketPort.onTrade 自行重读、累加、落盘同一个场次的价量统计，
     * 所以收尾不能用出清开始前的旧快照整体覆盖，否则统计被清零（丢失更新）。这里重读最新场次，
     * 只落本引擎负责的全日加权均价；场次状态机由 TradingSessionService.clear 的 finally 收尾。
     */
    private void saveClearPrice(String sessionId, double clearPrice) {
        TradingSession latest = simPort.findSession(sessionId);
        if (latest == null) {
            return;
        }
        latest.setClearPrice(Nums.price(clearPrice));
        latest.setUpdatedAt(System.currentTimeMillis());
        simPort.saveSession(latest);
    }

    /** 返回 [clearPrice, volume]。 */
    private double[] clearHour(int hour, List<SpotBid> bids, Map<String, TradingAccount> accounts,
                               TradingSession sess, Sim sim) {
        List<Leg> buys = legsOf(bids, hour, Side.BUY);
        List<Leg> sells = legsOf(bids, hour, Side.SELL);
        if (buys.isEmpty() || sells.isEmpty()) {
            return new double[]{0, 0};
        }
        buys.sort(Comparator.comparingDouble((Leg l) -> -l.price).thenComparing(l -> l.bid.getId()));
        sells.sort(Comparator.comparingDouble((Leg l) -> l.price).thenComparing(l -> l.bid.getId()));

        double clearPrice = findClearPrice(buys, sells, sess);
        if (!Nums.isPositive(clearPrice)) {
            return new double[]{0, 0};
        }
        List<Leg> eb = new ArrayList<>();
        for (Leg l : buys) {
            if (Nums.gte(l.price, clearPrice)) {
                eb.add(l);
            }
        }
        List<Leg> es = new ArrayList<>();
        for (Leg l : sells) {
            if (Nums.gte(clearPrice, l.price)) {
                es.add(l);
            }
        }
        double volume = Nums.qty(Math.min(total(eb), total(es)));
        if (!Nums.isPositive(volume)) {
            return new double[]{0, 0};
        }
        assign(eb, volume);
        assign(es, volume);

        double dealt = pair(eb, es, clearPrice, hour, accounts, sess, sim);
        return new double[]{clearPrice, Nums.qty(dealt)};
    }

    /** 与集中竞价同一套「最大成交量 + 区间取中点」规则，只是作用在小时段的报价曲线上。 */
    private double findClearPrice(List<Leg> buys, List<Leg> sells, TradingSession sess) {
        TreeSet<Double> candidates = new TreeSet<>();
        for (Leg l : buys) {
            candidates.add(l.price);
        }
        for (Leg l : sells) {
            candidates.add(l.price);
        }
        double best = 0, lo = 0, hi = 0;
        for (Double p : candidates) {
            double demand = 0;
            for (Leg b : buys) {
                if (Nums.gte(b.price, p)) {
                    demand += b.quantity;
                }
            }
            double supply = 0;
            for (Leg s : sells) {
                if (Nums.gte(p, s.price)) {
                    supply += s.quantity;
                }
            }
            double vol = Math.min(demand, supply);
            if (!Nums.isPositive(vol)) {
                continue;
            }
            if (Nums.gt(vol, best)) {
                best = vol;
                lo = p;
                hi = p;
            } else if (Nums.eq(vol, best)) {
                hi = p;
            }
        }
        if (!Nums.isPositive(best)) {
            return 0d;
        }
        double mid = Nums.alignTick((lo + hi) / 2, sess.getTickSize());
        return Nums.price(Nums.clamp(mid, sess.getPriceFloor(), sess.getPriceCap()));
    }

    /** 按报价优劣顺序把出清电量分配下去，多出的报价段自然落选。 */
    private void assign(List<Leg> legs, double volume) {
        double left = volume;
        for (Leg l : legs) {
            double a = Nums.qty(Math.min(l.quantity, Math.max(left, 0)));
            l.alloc = a;
            left = Nums.qty(left - a);
        }
    }

    private double pair(List<Leg> buys, List<Leg> sells, double price, int hour,
                        Map<String, TradingAccount> accounts, TradingSession sess, Sim sim) {
        double dealt = 0;
        int bi = 0, si = 0;
        while (bi < buys.size() && si < sells.size()) {
            Leg b = buys.get(bi);
            Leg s = sells.get(si);
            if (!Nums.isPositive(b.alloc)) {
                bi++;
                continue;
            }
            if (!Nums.isPositive(s.alloc)) {
                si++;
                continue;
            }
            double q = Nums.qty(Math.min(b.alloc, s.alloc));
            if (!Nums.isPositive(q)) {
                // 残量不足最小计量单位（0.001 MWh）时 q 会被舍成 0，两侧 alloc 与游标都不变，
                // 循环将永远出不去。只把带残渣的那一侧清零，另一侧的大额分配必须保留。
                if (b.alloc < s.alloc) {
                    b.alloc = 0;
                } else {
                    s.alloc = 0;
                }
                continue;
            }
            b.alloc = Nums.qty(b.alloc - q);
            s.alloc = Nums.qty(s.alloc - q);
            if (execute(b, s, q, price, hour, accounts, sess, sim)) {
                fillSegment(b.seg, q, price);
                fillSegment(s.seg, q, price);
                dealt = Nums.qty(dealt + q);
            }
        }
        return dealt;
    }

    private boolean execute(Leg buyLeg, Leg sellLeg, double qty, double price, int hour,
                            Map<String, TradingAccount> accounts, TradingSession sess, Sim sim) {
        TradingAccount buyer = accounts.get(buyLeg.bid.getAccountId());
        TradingAccount seller = accounts.get(sellLeg.bid.getAccountId());
        if (buyer == null || seller == null || buyer.getId().equals(seller.getId())) {
            return false;
        }
        String ref = "SPOT:" + sess.getId() + ":" + hour;
        try {
            double buyReserve = riskService.freezeBuy(buyer, price, qty, sim, ref);
            double sellReserve;
            try {
                sellReserve = riskService.freezeSell(seller, price, qty, sim, ref);
            } catch (RuntimeException e) {
                riskService.release(buyer.getId(), Side.BUY, buyReserve, ref);
                throw e;
            }
            riskService.release(buyer.getId(), Side.BUY, buyReserve, ref);
            riskService.release(seller.getId(), Side.SELL, sellReserve, ref);

            Trade t = contractService.newTrade(sim, sess.getId(), sess.getVariety(), sess.getPeriodType(),
                    TradeMode.SPOT_CLEARING, buyLeg.bid.getId(), sellLeg.bid.getId(),
                    buyer, seller, price, qty, Side.BUY);
            String day = buyLeg.bid.getTradeDate() != null ? buyLeg.bid.getTradeDate() : sess.getDeliveryStart();
            contractService.commitTrade(t, day, day);
            return true;
        } catch (Exception e) {
            // 单个主体资金不足不应拖垮整场出清，记账后跳过该配对
            log.warn("现货出清成交失败 hour={} buyer={} seller={}: {}",
                    hour, buyer.getId(), seller.getId(), e.getMessage());
            return false;
        }
    }

    private void fillSegment(SpotSegment seg, double qty, double price) {
        seg.setClearedQty(Nums.qty(seg.getClearedQty() + qty));
        seg.setClearedPrice(Nums.price(price));
    }

    private List<Leg> legsOf(List<SpotBid> bids, int hour, Side side) {
        List<Leg> out = new ArrayList<>();
        for (SpotBid b : bids) {
            if (b.getSide() != side || b.getSegments() == null) {
                continue;
            }
            for (SpotSegment s : b.getSegments()) {
                if (s.getHour() == hour && Nums.isPositive(s.getQuantity())) {
                    out.add(new Leg(b, s));
                }
            }
        }
        return out;
    }

    private double total(List<Leg> legs) {
        double q = 0;
        for (Leg l : legs) {
            q += l.quantity;
        }
        return Nums.qty(q);
    }

    private void safePush(String topic, Object data) {
        try {
            wsPort.push(topic, data);
        } catch (Exception ignored) {
            // 推送失败不影响出清
        }
    }

    private void safeAudit(String simId, String action, String target, String detail) {
        try {
            auditPort.system(simId, action, target, detail);
        } catch (Exception ignored) {
            // 审计失败不影响出清
        }
    }
}
