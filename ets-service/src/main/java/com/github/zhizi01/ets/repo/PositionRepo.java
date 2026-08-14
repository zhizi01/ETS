package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Position;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 持仓以 Hash 存放：key = ets:acct:{id}:pos，field = instrumentKey。 */
@Repository
@RequiredArgsConstructor
public class PositionRepo {

    private final RedisStore store;

    public Position find(String accountId, String instrumentKey) {
        return store.hGetJson(RedisKeys.acctPos(accountId), instrumentKey, Position.class);
    }

    public Position findOrCreate(String accountId, String instrumentKey) {
        Position p = find(accountId, instrumentKey);
        if (p == null) {
            p = new Position();
            p.setAccountId(accountId);
            p.setInstrumentKey(instrumentKey);
        }
        return p;
    }

    public void save(Position p) {
        p.setUpdatedAt(System.currentTimeMillis());
        store.hSetJson(RedisKeys.acctPos(p.getAccountId()), p.getInstrumentKey(), p);
    }

    public List<Position> listByAccount(String accountId) {
        return store.hValuesJson(RedisKeys.acctPos(accountId), Position.class);
    }

    public void clear(String accountId) {
        store.delete(RedisKeys.acctPos(accountId));
    }
}
