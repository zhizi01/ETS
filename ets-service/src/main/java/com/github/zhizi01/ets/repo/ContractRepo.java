package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Contract;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ContractRepo extends BaseRepo<Contract> {

    public ContractRepo(RedisStore store) {
        super(store, Contract.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.contract(id);
    }

    public void saveContract(Contract c) {
        save(c.getId(), c);
        store.zAdd(RedisKeys.acctContracts(c.getBuyAccountId()), c.getId(), c.getSignedAt());
        store.zAdd(RedisKeys.acctContracts(c.getSellAccountId()), c.getId(), c.getSignedAt());
    }

    public List<Contract> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctContracts(accountId)));
    }

    public long countByAccount(String accountId) {
        return store.zCard(RedisKeys.acctContracts(accountId));
    }
}
