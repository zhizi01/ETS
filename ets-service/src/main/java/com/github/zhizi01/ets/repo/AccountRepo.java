package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.TradingAccount;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public class AccountRepo extends BaseRepo<TradingAccount> {

    public AccountRepo(RedisStore store) {
        super(store, TradingAccount.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.acct(id);
    }

    public void saveAccount(TradingAccount a) {
        save(a.getId(), a);
        store.sAdd(RedisKeys.simAccounts(a.getSimId()), a.getId());
        store.hSet(RedisKeys.idxAcctCode(a.getSimId()), a.getCode(), a.getId());
        if (a.getUserId() != null) {
            store.sAdd(RedisKeys.userAccts(a.getUserId(), a.getSimId()), a.getId());
        }
    }

    public void removeAccount(TradingAccount a) {
        remove(a.getId());
        store.sRem(RedisKeys.simAccounts(a.getSimId()), a.getId());
        store.hDel(RedisKeys.idxAcctCode(a.getSimId()), a.getCode());
        if (a.getUserId() != null) {
            store.sRem(RedisKeys.userAccts(a.getUserId(), a.getSimId()), a.getId());
        }
        store.delete(RedisKeys.acctPos(a.getId()));
    }

    public List<TradingAccount> listBySim(String simId) {
        return findAll(store.sMembers(RedisKeys.simAccounts(simId)));
    }

    public Set<String> idsBySim(String simId) {
        return store.sMembers(RedisKeys.simAccounts(simId));
    }

    public List<TradingAccount> listByUser(String userId, String simId) {
        return findAll(store.sMembers(RedisKeys.userAccts(userId, simId)));
    }

    public long countByUser(String userId, String simId) {
        return store.sCard(RedisKeys.userAccts(userId, simId));
    }

    public TradingAccount findByCode(String simId, String code) {
        String id = store.hGet(RedisKeys.idxAcctCode(simId), code);
        return id == null ? null : find(id);
    }

    public boolean codeExists(String simId, String code) {
        return store.hGet(RedisKeys.idxAcctCode(simId), code) != null;
    }
}
