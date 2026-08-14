package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Quote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class QuoteRepo {

    private final RedisStore store;
    private final SimRepo simRepo;

    public Quote find(String simId, String instrumentKey) {
        return store.get(RedisKeys.quote(simId, instrumentKey), Quote.class);
    }

    public void save(Quote q) {
        q.setUpdatedAt(System.currentTimeMillis());
        store.set(RedisKeys.quote(q.getSimId(), q.getInstrumentKey()), q);
        simRepo.addInstrument(q.getSimId(), q.getInstrumentKey());
    }

    /** 该模拟盘下全部标的的行情快照 */
    public List<Quote> listBySim(String simId) {
        Set<String> keys = simRepo.instruments(simId);
        List<String> redisKeys = new ArrayList<>(keys.size());
        for (String ik : keys) {
            redisKeys.add(RedisKeys.quote(simId, ik));
        }
        return store.multiGet(redisKeys, Quote.class);
    }
}
