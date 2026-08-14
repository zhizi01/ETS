package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.TakeOrder;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TakeOrderRepo extends BaseRepo<TakeOrder> {

    public TakeOrderRepo(RedisStore store) {
        super(store, TakeOrder.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.take(id);
    }

    public void saveTake(TakeOrder t) {
        save(t.getId(), t);
        store.zAdd(RedisKeys.listingTakes(t.getListingId()), t.getId(), t.getCreatedAt());
    }

    public List<TakeOrder> listByListing(String listingId) {
        return findAll(store.zRange(RedisKeys.listingTakes(listingId)));
    }
}
