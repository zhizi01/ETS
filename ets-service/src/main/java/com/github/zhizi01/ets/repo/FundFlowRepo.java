package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.FundFlow;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FundFlowRepo extends BaseRepo<FundFlow> {

    public FundFlowRepo(RedisStore store) {
        super(store, FundFlow.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.flow(id);
    }

    public void saveFlow(FundFlow f) {
        save(f.getId(), f);
        store.zAdd(RedisKeys.acctFlow(f.getAccountId()), f.getId(), f.getCreatedAt());
    }

    /** 按时间倒序取账户流水 */
    public List<FundFlow> listByAccount(String accountId, int limit) {
        return findAll(store.zRevRange(RedisKeys.acctFlow(accountId), 0, Math.max(limit - 1, 0)));
    }

    public List<FundFlow> listAllByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctFlow(accountId)));
    }

    public long countByAccount(String accountId) {
        return store.zCard(RedisKeys.acctFlow(accountId));
    }
}
