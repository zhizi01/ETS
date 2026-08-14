package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.repo.OrderRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 sessionId 一本订单簿。
 * <p>
 * 采用懒加载而非启动期全量重建：首次访问某场次时才从 Redis 镜像还原，
 * 这样既不与其它模块的 ApplicationRunner 抢启动顺序，也避免为历史场次白白占内存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookRegistry {

    private final OrderRepo orderRepo;

    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();

    /** 取（必要时重建）某场次的订单簿。 */
    public OrderBook book(String sessionId) {
        return books.computeIfAbsent(sessionId, this::rebuild);
    }

    /** 只读探测，不触发重建。 */
    public OrderBook peek(String sessionId) {
        return books.get(sessionId);
    }

    /** 场次结束后释放内存，镜像由调用方决定是否一并清除。 */
    public void drop(String sessionId) {
        books.remove(sessionId);
    }

    public Depth depth(String sessionId, int level) {
        return book(sessionId).depth(level);
    }

    public int bookCount() {
        return books.size();
    }

    /** 常驻内存的挂单总数，供管理端指标使用。 */
    public int totalOrders() {
        int n = 0;
        for (Map.Entry<String, OrderBook> e : books.entrySet()) {
            n += e.getValue().size();
        }
        return n;
    }

    /**
     * 从 Redis 镜像还原。必须先按 seq 升序排序：ZSET 只保证 score（价格档位）有序，
     * 同档位内的成员顺序是字典序，直接回放会破坏「时间优先」。
     */
    private OrderBook rebuild(String sessionId) {
        OrderBook book = new OrderBook(sessionId, orderRepo);
        try {
            List<Order> mirrored = new ArrayList<>(orderRepo.allBookOrders(sessionId));
            mirrored.sort(Comparator.comparingLong(Order::getSeq));
            int n = 0;
            for (Order o : mirrored) {
                if (o.getStatus() != null && o.getStatus().isOpen() && o.remainQty() > 1e-9) {
                    book.restore(o);
                    n++;
                } else {
                    // 镜像里的残留（已撤/已成）顺手清掉，避免越积越多
                    orderRepo.mirrorRemove(sessionId, o.getSide(), o.getId());
                }
            }
            if (n > 0) {
                log.info("订单簿从 Redis 镜像重建 session={} orders={}", sessionId, n);
            }
        } catch (Exception e) {
            log.warn("订单簿重建失败 session={}，以空簿启动: {}", sessionId, e.getMessage());
        }
        return book;
    }
}
