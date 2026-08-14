package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Bill;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BillRepo extends BaseRepo<Bill> {

    public BillRepo(RedisStore store) {
        super(store, Bill.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.bill(id);
    }

    public void saveBill(Bill b) {
        save(b.getId(), b);
        store.zAdd(RedisKeys.acctBills(b.getAccountId()), b.getId(), b.getSettledAt());
        store.zAdd(RedisKeys.simBills(b.getSimId(), b.getPeriod()), b.getId(), -b.getProfit());
    }

    public List<Bill> listByAccount(String accountId) {
        return findAll(store.zRevRangeAll(RedisKeys.acctBills(accountId)));
    }

    /** 按利润从高到低取该周期的全部结算单 */
    public List<Bill> listBySimPeriod(String simId, String period) {
        return findAll(store.zRange(RedisKeys.simBills(simId, period)));
    }
}
