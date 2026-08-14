package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.AuditLog;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AuditLogRepo extends BaseRepo<AuditLog> {

    /** 审计日志只保留最近这么多条，避免无限增长 */
    private static final int KEEP = 5000;

    public AuditLogRepo(RedisStore store) {
        super(store, AuditLog.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.log(id);
    }

    public void saveLog(AuditLog l) {
        save(l.getId(), l);
        store.zAdd(RedisKeys.auditIndex(), l.getId(), l.getCreatedAt());
        long size = store.zCard(RedisKeys.auditIndex());
        if (size > KEEP + 500) {
            List<String> stale = store.zRange(RedisKeys.auditIndex(), 0, size - KEEP - 1);
            if (!stale.isEmpty()) {
                store.zRem(RedisKeys.auditIndex(), stale.toArray(new String[0]));
                stale.forEach(this::remove);
            }
        }
    }

    public List<AuditLog> recent(int limit) {
        return findAll(store.zRevRange(RedisKeys.auditIndex(), 0, Math.max(limit - 1, 0)));
    }

    public long count() {
        return store.zCard(RedisKeys.auditIndex());
    }
}
