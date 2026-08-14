package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Order;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OrderRepo extends BaseRepo<Order> {

    public OrderRepo(RedisStore store) {
        super(store, Order.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.order(id);
    }

    public void saveOrder(Order o) {
        save(o.getId(), o);
        store.zAdd(RedisKeys.acctOrders(o.getAccountId()), o.getId(), o.getCreatedAt());
        store.zAdd(RedisKeys.sessOrders(o.getSessionId()), o.getId(), o.getSeq());
    }

    /** 仅更新实体，不动索引（撮合过程中的高频写） */
    public void update(Order o) {
        o.setUpdatedAt(System.currentTimeMillis());
        save(o.getId(), o);
    }

    public List<Order> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctOrders(accountId)));
    }

    public List<Order> listBySession(String sessionId) {
        return findAll(store.zRange(RedisKeys.sessOrders(sessionId)));
    }

    public long countBySession(String sessionId) {
        return store.zCard(RedisKeys.sessOrders(sessionId));
    }

    // ── 订单簿镜像（权威簿在 JVM 内存，这里仅供重启重建与只读查询） ──

    public void mirrorAdd(String sessionId, Side side, String orderId, double score) {
        store.zAdd(side == Side.BUY ? RedisKeys.obBid(sessionId) : RedisKeys.obAsk(sessionId), orderId, score);
    }

    public void mirrorRemove(String sessionId, Side side, String orderId) {
        store.zRem(side == Side.BUY ? RedisKeys.obBid(sessionId) : RedisKeys.obAsk(sessionId), orderId);
    }

    /** 买盘 score 为 -price*100，升序即价高者先 */
    public List<Order> bookBids(String sessionId, int limit) {
        return findAll(store.zRange(RedisKeys.obBid(sessionId), 0, Math.max(limit - 1, 0)));
    }

    /** 卖盘 score 为 price*100，升序即价低者先 */
    public List<Order> bookAsks(String sessionId, int limit) {
        return findAll(store.zRange(RedisKeys.obAsk(sessionId), 0, Math.max(limit - 1, 0)));
    }

    public List<Order> allBookOrders(String sessionId) {
        List<Order> out = findAll(store.zRange(RedisKeys.obBid(sessionId)));
        out.addAll(findAll(store.zRange(RedisKeys.obAsk(sessionId))));
        return out;
    }

    public void clearBook(String sessionId) {
        store.delete(RedisKeys.obBid(sessionId));
        store.delete(RedisKeys.obAsk(sessionId));
    }
}
