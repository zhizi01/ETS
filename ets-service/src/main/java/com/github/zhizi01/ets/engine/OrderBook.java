package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.repo.OrderRepo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单个交易场次的权威订单簿，常驻 JVM 内存，Redis 只作镜像用于重启重建。
 * <p>
 * 不变量：
 * <ol>
 *   <li>买盘档位 key = -round(price×100)，卖盘 key = round(price×100)。两侧都按 key 升序遍历即「价格优先」。</li>
 *   <li>同一档位内用 ArrayDeque 保持入队顺序，而入队顺序即 seq 升序，出队即「时间优先」。</li>
 *   <li>index 与两棵 TreeMap 严格同步；档位队列空了必须立刻摘掉，否则 bestBid/bestAsk 会读到空档。</li>
 *   <li>全部公开方法 synchronized，撮合时对同一个 book 对象加锁即可让该场次完全串行。</li>
 * </ol>
 */
@Slf4j
public class OrderBook {

    private final String sessionId;
    /** 为 null 时为纯内存簿（单测/重建场景），不写镜像 */
    private final OrderRepo orderRepo;

    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>();
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private final Map<String, Order> index = new HashMap<>();

    public OrderBook(String sessionId, OrderRepo orderRepo) {
        this.sessionId = sessionId;
        this.orderRepo = orderRepo;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** 档位 score，同时用作 Redis 镜像 ZSET 的 score。 */
    public static long score(Side side, double price) {
        long cents = Math.round(price * 100);
        return side == Side.BUY ? -cents : cents;
    }

    // ── 增删 ────────────────────────────────────────────────

    /** 入簿并同步镜像。调用方需保证 order 仍有未成交余量。 */
    public synchronized void add(Order o) {
        place(o);
        mirrorAdd(o);
    }

    /** 从 Redis 镜像重建时使用：只入内存，不回写镜像。 */
    public synchronized void restore(Order o) {
        place(o);
    }

    /** 摘单并同步镜像，返回被摘掉的订单；不存在返回 null。 */
    public synchronized Order remove(String orderId) {
        Order o = index.remove(orderId);
        if (o == null) {
            return null;
        }
        TreeMap<Long, ArrayDeque<Order>> sideMap = mapOf(o.getSide());
        long key = score(o.getSide(), o.getPrice());
        ArrayDeque<Order> q = sideMap.get(key);
        if (q != null) {
            q.removeIf(x -> x.getId().equals(orderId));
            if (q.isEmpty()) {
                sideMap.remove(key);
            }
        }
        mirrorRemove(o);
        return o;
    }

    public synchronized Order get(String orderId) {
        return index.get(orderId);
    }

    public synchronized boolean contains(String orderId) {
        return index.containsKey(orderId);
    }

    /** 清空整簿并删除镜像。场次闭市或重置演练时调用。 */
    public synchronized void clear() {
        bids.clear();
        asks.clear();
        index.clear();
        if (orderRepo != null) {
            try {
                orderRepo.clearBook(sessionId);
            } catch (Exception e) {
                log.warn("清空订单簿镜像失败 session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    // ── 盘口读取 ────────────────────────────────────────────

    /** 买一（价最高、同价最早）。 */
    public synchronized Order bestBid() {
        return first(bids);
    }

    /** 卖一（价最低、同价最早）。 */
    public synchronized Order bestAsk() {
        return first(asks);
    }

    public synchronized double bestBidPrice() {
        Order o = first(bids);
        return o == null ? 0d : o.getPrice();
    }

    public synchronized double bestAskPrice() {
        Order o = first(asks);
        return o == null ? 0d : o.getPrice();
    }

    /** 两侧都有报价时取中间价，否则退化为单边价，空簿返回 0。 */
    public synchronized double midPrice() {
        double b = bestBidPrice();
        double a = bestAskPrice();
        if (Nums.isPositive(b) && Nums.isPositive(a)) {
            return Nums.price((a + b) / 2);
        }
        return Nums.isPositive(b) ? b : a;
    }

    /** 指定档数的盘口快照。 */
    public synchronized Depth depth(int level) {
        int lv = Math.max(level, 1);
        Depth d = new Depth();
        d.setSessionId(sessionId);
        d.setBids(levels(bids, lv));
        d.setAsks(levels(asks, lv));
        d.setBidOrderCount(countOf(bids));
        d.setAskOrderCount(countOf(asks));
        d.setBidTotalQty(totalQtyOf(bids));
        d.setAskTotalQty(totalQtyOf(asks));
        return d;
    }

    /** 按优先级顺序的单边快照，供 FOK 试算与集中竞价读取。 */
    public synchronized List<Order> orders(Side side) {
        List<Order> out = new ArrayList<>();
        for (ArrayDeque<Order> q : mapOf(side).values()) {
            out.addAll(q);
        }
        return out;
    }

    public synchronized List<Order> allOrders() {
        List<Order> out = orders(Side.BUY);
        out.addAll(orders(Side.SELL));
        return out;
    }

    public synchronized List<Order> ordersByAccount(String accountId) {
        List<Order> out = new ArrayList<>();
        for (Order o : index.values()) {
            if (o.getAccountId() != null && o.getAccountId().equals(accountId)) {
                out.add(o);
            }
        }
        return out;
    }

    public synchronized int size() {
        return index.size();
    }

    public synchronized boolean isEmpty() {
        return index.isEmpty();
    }

    // ── 内部 ────────────────────────────────────────────────

    private void place(Order o) {
        if (o == null || o.getId() == null || index.containsKey(o.getId())) {
            return;
        }
        mapOf(o.getSide())
                .computeIfAbsent(score(o.getSide(), o.getPrice()), k -> new ArrayDeque<>())
                .addLast(o);
        index.put(o.getId(), o);
    }

    private TreeMap<Long, ArrayDeque<Order>> mapOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private Order first(TreeMap<Long, ArrayDeque<Order>> map) {
        for (Map.Entry<Long, ArrayDeque<Order>> e : map.entrySet()) {
            Order o = e.getValue().peekFirst();
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    private List<Depth.Level> levels(TreeMap<Long, ArrayDeque<Order>> map, int level) {
        List<Depth.Level> out = new ArrayList<>(level);
        for (ArrayDeque<Order> q : map.values()) {
            if (out.size() >= level) {
                break;
            }
            double qty = 0;
            int cnt = 0;
            double price = 0;
            for (Order o : q) {
                qty += o.remainQty();
                cnt++;
                price = o.getPrice();
            }
            if (cnt > 0) {
                out.add(new Depth.Level(Nums.price(price), Nums.qty(qty), cnt));
            }
        }
        return out;
    }

    private int countOf(TreeMap<Long, ArrayDeque<Order>> map) {
        int n = 0;
        for (ArrayDeque<Order> q : map.values()) {
            n += q.size();
        }
        return n;
    }

    private double totalQtyOf(TreeMap<Long, ArrayDeque<Order>> map) {
        double q = 0;
        for (ArrayDeque<Order> d : map.values()) {
            for (Order o : d) {
                q += o.remainQty();
            }
        }
        return Nums.qty(q);
    }

    private void mirrorAdd(Order o) {
        if (orderRepo == null) {
            return;
        }
        try {
            orderRepo.mirrorAdd(sessionId, o.getSide(), o.getId(), score(o.getSide(), o.getPrice()));
        } catch (Exception e) {
            log.warn("订单簿镜像写入失败 order={}: {}", o.getId(), e.getMessage());
        }
    }

    private void mirrorRemove(Order o) {
        if (orderRepo == null) {
            return;
        }
        try {
            orderRepo.mirrorRemove(sessionId, o.getSide(), o.getId());
        } catch (Exception e) {
            log.warn("订单簿镜像删除失败 order={}: {}", o.getId(), e.getMessage());
        }
    }
}
