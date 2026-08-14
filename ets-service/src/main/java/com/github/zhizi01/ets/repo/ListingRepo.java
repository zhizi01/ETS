package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Listing;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ListingRepo extends BaseRepo<Listing> {

    public ListingRepo(RedisStore store) {
        super(store, Listing.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.listing(id);
    }

    public void saveListing(Listing l) {
        save(l.getId(), l);
        store.zAdd(RedisKeys.sessListings(l.getSessionId()), l.getId(), l.getCreatedAt());
        store.zAdd(RedisKeys.acctListings(l.getAccountId()), l.getId(), l.getCreatedAt());
    }

    public void update(Listing l) {
        l.setUpdatedAt(System.currentTimeMillis());
        save(l.getId(), l);
    }

    /** 最新挂牌在前 */
    public List<Listing> listBySession(String sessionId) {
        return findAll(store.zRevRangeAll(RedisKeys.sessListings(sessionId)));
    }

    public List<Listing> recentBySession(String sessionId, int limit) {
        return findAll(store.zRevRange(RedisKeys.sessListings(sessionId), 0, Math.max(limit - 1, 0)));
    }

    public List<Listing> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctListings(accountId)));
    }

    public long countBySession(String sessionId) {
        return store.zCard(RedisKeys.sessListings(sessionId));
    }
}
