package com.github.zhizi01.ets.service.market;

import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.OrderRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 盘口聚合。数据源是 Redis 里的订单簿镜像（权威簿在撮合引擎的 JVM 内存中）。
 */
@Slf4j
@Service
public class DepthService {

    /** 单侧最多回溯这么多笔委托做聚合，足够覆盖前若干档并统计总量 */
    private static final int SCAN_LIMIT = 500;

    private final OrderRepo orderRepo;
    private final SimPort simPort;

    public DepthService(OrderRepo orderRepo, @Lazy SimPort simPort) {
        this.orderRepo = orderRepo;
        this.simPort = simPort;
    }

    public Depth depth(String sessionId, int level) {
        Depth d = new Depth();
        d.setSessionId(sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return d;
        }
        int lv = (int) Nums.clamp(level <= 0 ? 5 : level, 1, 20);

        Map<Double, double[]> bidMap = new TreeMap<>(Collections.reverseOrder());
        Map<Double, double[]> askMap = new TreeMap<>();
        double bidQty = collect(safeBids(sessionId), bidMap);
        double askQty = collect(safeAsks(sessionId), askMap);

        d.setBidOrderCount(countOrders(bidMap));
        d.setAskOrderCount(countOrders(askMap));
        d.setBidTotalQty(Nums.qty(bidQty));
        d.setAskTotalQty(Nums.qty(askQty));

        TradingSession s = safeSession(sessionId);
        // 集中竞价的申报阶段盘口对所有人保密，只能看到申报笔数与总量
        boolean hidden = s != null && s.getState() == TSessionState.PRE_OPEN;
        d.setHidden(hidden);
        if (!hidden) {
            d.setBids(toLevels(bidMap, lv));
            d.setAsks(toLevels(askMap, lv));
        }
        d.setTs(System.currentTimeMillis());
        return d;
    }

    /** 返回 {买一, 卖一}，缺失的一侧为 0 */
    public double[] best(String sessionId) {
        double bid = 0;
        double ask = 0;
        for (Order o : safeBids(sessionId)) {
            if (alive(o) && (bid == 0 || o.getPrice() > bid)) {
                bid = o.getPrice();
            }
        }
        for (Order o : safeAsks(sessionId)) {
            if (alive(o) && (ask == 0 || o.getPrice() < ask)) {
                ask = o.getPrice();
            }
        }
        return new double[]{bid, ask};
    }

    /** 买一/卖一及其挂单量，供行情快照回填 */
    public double[] bestWithQty(String sessionId) {
        Map<Double, double[]> bidMap = new TreeMap<>(Collections.reverseOrder());
        Map<Double, double[]> askMap = new TreeMap<>();
        collect(safeBids(sessionId), bidMap);
        collect(safeAsks(sessionId), askMap);
        double[] out = new double[4];
        for (Map.Entry<Double, double[]> e : bidMap.entrySet()) {
            out[0] = e.getKey();
            out[1] = Nums.qty(e.getValue()[0]);
            break;
        }
        for (Map.Entry<Double, double[]> e : askMap.entrySet()) {
            out[2] = e.getKey();
            out[3] = Nums.qty(e.getValue()[0]);
            break;
        }
        return out;
    }

    public long bookSize(String sessionId) {
        return (long) safeBids(sessionId).size() + safeAsks(sessionId).size();
    }

    // ── 内部 ────────────────────────────────────────────────

    private double collect(List<Order> orders, Map<Double, double[]> into) {
        double total = 0;
        for (Order o : orders) {
            if (!alive(o)) {
                continue;
            }
            double remain = o.remainQty();
            double price = Nums.price(o.getPrice());
            double[] agg = into.computeIfAbsent(price, k -> new double[2]);
            agg[0] += remain;
            agg[1] += 1;
            total += remain;
        }
        return total;
    }

    private List<Depth.Level> toLevels(Map<Double, double[]> map, int level) {
        List<Depth.Level> out = new java.util.ArrayList<>(level);
        for (Map.Entry<Double, double[]> e : map.entrySet()) {
            if (out.size() >= level) {
                break;
            }
            out.add(new Depth.Level(e.getKey(), Nums.qty(e.getValue()[0]), (int) e.getValue()[1]));
        }
        return out;
    }

    private int countOrders(Map<Double, double[]> map) {
        int n = 0;
        for (double[] v : map.values()) {
            n += (int) v[1];
        }
        return n;
    }

    private boolean alive(Order o) {
        return o != null
                && (o.getStatus() == OrderStatus.ACTIVE || o.getStatus() == OrderStatus.PARTIAL)
                && o.remainQty() > 1e-9
                && o.getPrice() > 0;
    }

    private List<Order> safeBids(String sessionId) {
        try {
            return orderRepo.bookBids(sessionId, SCAN_LIMIT);
        } catch (Exception e) {
            log.debug("读取买盘失败 {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private List<Order> safeAsks(String sessionId) {
        try {
            return orderRepo.bookAsks(sessionId, SCAN_LIMIT);
        } catch (Exception e) {
            log.debug("读取卖盘失败 {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private TradingSession safeSession(String sessionId) {
        try {
            return simPort.findSession(sessionId);
        } catch (Exception e) {
            return null;
        }
    }
}
