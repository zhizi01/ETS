package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Fundamental;
import com.github.zhizi01.ets.domain.model.Sim;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public class SimRepo extends BaseRepo<Sim> {

    public SimRepo(RedisStore store) {
        super(store, Sim.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.sim(id);
    }

    public void saveSim(Sim s) {
        save(s.getId(), s);
        store.zAdd(RedisKeys.simAll(), s.getId(), s.getCreatedAt());
    }

    /** 全部模拟盘，创建时间倒序 */
    public List<Sim> listAll() {
        return findAll(store.zRevRangeAll(RedisKeys.simAll()));
    }

    public void removeSim(String simId) {
        remove(simId);
        store.zRem(RedisKeys.simAll(), simId);
    }

    // ── 基本面 ──────────────────────────────────────────
    public Fundamental findFundamental(String simId) {
        return store.get(RedisKeys.simFundamental(simId), Fundamental.class);
    }

    public void saveFundamental(Fundamental f) {
        store.set(RedisKeys.simFundamental(f.getSimId()), f);
    }

    // ── 标的清单 ────────────────────────────────────────
    public void addInstrument(String simId, String instrumentKey) {
        store.sAdd(RedisKeys.simInstruments(simId), instrumentKey);
    }

    public Set<String> instruments(String simId) {
        return store.sMembers(RedisKeys.simInstruments(simId));
    }

    // ── 排行榜 ──────────────────────────────────────────
    public void updateRank(String simId, String accountId, double totalPnl) {
        store.zAdd(RedisKeys.simRank(simId), accountId, totalPnl);
    }

    public List<String> topAccounts(String simId, int limit) {
        return store.zRevRange(RedisKeys.simRank(simId), 0, Math.max(limit - 1, 0));
    }

    public long rankOf(String simId, String accountId) {
        return store.zRevRank(RedisKeys.simRank(simId), accountId) + 1;
    }

    /** 删除该模拟盘下的全部数据，管理端「重置演练」使用 */
    public long purge(String simId) {
        long n = 0;
        n += store.deleteByPattern(RedisKeys.P + "sim:" + simId + ":*");
        n += store.deleteByPattern(RedisKeys.P + "idx:acct:code:" + simId);
        n += store.deleteByPattern(RedisKeys.P + "quote:" + simId + ":*");
        n += store.deleteByPattern(RedisKeys.P + "kline:" + simId + ":*");
        return n;
    }
}
