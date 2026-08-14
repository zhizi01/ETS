package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.TradingSession;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TradingSessionRepo extends BaseRepo<TradingSession> {

    public TradingSessionRepo(RedisStore store) {
        super(store, TradingSession.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.tsess(id);
    }

    public void saveSession(TradingSession s) {
        save(s.getId(), s);
        store.zAdd(RedisKeys.simSessions(s.getSimId()), s.getId(), s.getOpenTime());
    }

    public void removeSession(TradingSession s) {
        remove(s.getId());
        store.zRem(RedisKeys.simSessions(s.getSimId()), s.getId());
        store.delete(RedisKeys.obBid(s.getId()));
        store.delete(RedisKeys.obAsk(s.getId()));
    }

    /** 按开市时间升序 */
    public List<TradingSession> listBySim(String simId) {
        return findAll(store.zRange(RedisKeys.simSessions(simId)));
    }

    public List<String> idsBySim(String simId) {
        return store.zRange(RedisKeys.simSessions(simId));
    }

    public long countBySim(String simId) {
        return store.zCard(RedisKeys.simSessions(simId));
    }
}
