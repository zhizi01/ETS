package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 实体仓储基类：实体本身以 JSON 存在 {@link #key(String)} 上，
 * 排序与筛选依赖各仓储自己维护的 ZSET / SET 索引。
 */
public abstract class BaseRepo<T> {

    protected final RedisStore store;
    private final Class<T> type;

    protected BaseRepo(RedisStore store, Class<T> type) {
        this.store = store;
        this.type = type;
    }

    protected abstract String key(String id);

    public T find(String id) {
        return id == null ? null : store.get(key(id), type);
    }

    public T require(String id, ErrorCode notFound) {
        T t = find(id);
        if (t == null) {
            throw new BizException(notFound);
        }
        return t;
    }

    public boolean exists(String id) {
        return id != null && store.exists(key(id));
    }

    public void save(String id, T entity) {
        store.set(key(id), entity);
    }

    public void remove(String id) {
        store.delete(key(id));
    }

    public List<T> findAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> keys = new ArrayList<>(ids.size());
        for (String id : ids) {
            keys.add(key(id));
        }
        return store.multiGet(keys, type);
    }
}
