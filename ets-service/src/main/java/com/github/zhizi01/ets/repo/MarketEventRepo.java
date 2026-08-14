package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.MarketEvent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class MarketEventRepo extends BaseRepo<MarketEvent> {

    public MarketEventRepo(RedisStore store) {
        super(store, MarketEvent.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.event(id);
    }

    public void saveEvent(MarketEvent e) {
        save(e.getId(), e);
        store.zAdd(RedisKeys.simEvents(e.getSimId()), e.getId(), e.getEffectiveAt());
    }

    public void removeEvent(MarketEvent e) {
        remove(e.getId());
        store.zRem(RedisKeys.simEvents(e.getSimId()), e.getId());
    }

    /** 最新事件在前 */
    public List<MarketEvent> listBySim(String simId) {
        return findAll(store.zRevRangeAll(RedisKeys.simEvents(simId)));
    }

    /** 当前生效中的事件 */
    public List<MarketEvent> activeBySim(String simId, long now) {
        return listBySim(simId).stream().filter(e -> e.active(now)).collect(Collectors.toList());
    }
}
