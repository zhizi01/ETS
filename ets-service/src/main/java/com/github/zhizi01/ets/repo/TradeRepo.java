package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Trade;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TradeRepo extends BaseRepo<Trade> {

    public TradeRepo(RedisStore store) {
        super(store, Trade.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.trade(id);
    }

    public void saveTrade(Trade t) {
        save(t.getId(), t);
        store.zAdd(RedisKeys.sessTrades(t.getSessionId()), t.getId(), t.getTradedAt());
        store.zAdd(RedisKeys.acctTrades(t.getBuyAccountId()), t.getId(), t.getTradedAt());
        store.zAdd(RedisKeys.acctTrades(t.getSellAccountId()), t.getId(), t.getTradedAt());
    }

    /** 最新成交在前 */
    public List<Trade> recentBySession(String sessionId, int limit) {
        return findAll(store.zRevRange(RedisKeys.sessTrades(sessionId), 0, Math.max(limit - 1, 0)));
    }

    public List<Trade> listBySession(String sessionId) {
        return findAll(store.zRange(RedisKeys.sessTrades(sessionId)));
    }

    public List<Trade> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctTrades(accountId)));
    }

    public List<Trade> recentByAccount(String accountId, int limit) {
        return findAll(store.zRevRange(RedisKeys.acctTrades(accountId), 0, Math.max(limit - 1, 0)));
    }

    /** 取某时间段内的成交，用于 K 线补算 */
    public List<Trade> bySessionBetween(String sessionId, long fromTs, long toTs) {
        return findAll(store.zRangeByScore(RedisKeys.sessTrades(sessionId), fromTs, toTs));
    }

    public long countBySession(String sessionId) {
        return store.zCard(RedisKeys.sessTrades(sessionId));
    }

    public long countByAccount(String accountId) {
        return store.zCard(RedisKeys.acctTrades(accountId));
    }
}
