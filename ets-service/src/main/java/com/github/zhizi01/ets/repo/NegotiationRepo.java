package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Negotiation;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NegotiationRepo extends BaseRepo<Negotiation> {

    public NegotiationRepo(RedisStore store) {
        super(store, Negotiation.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.nego(id);
    }

    public void saveNego(Negotiation n) {
        save(n.getId(), n);
        store.zAdd(RedisKeys.acctNegos(n.getInitiatorAccountId()), n.getId(), n.getCreatedAt());
        store.zAdd(RedisKeys.acctNegos(n.getCounterpartyAccountId()), n.getId(), n.getCreatedAt());
    }

    public void update(Negotiation n) {
        n.setUpdatedAt(System.currentTimeMillis());
        save(n.getId(), n);
    }

    /** 该账户参与的全部协商，最新在前 */
    public List<Negotiation> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctNegos(accountId)));
    }

    public long countByAccount(String accountId) {
        return store.zCard(RedisKeys.acctNegos(accountId));
    }
}
