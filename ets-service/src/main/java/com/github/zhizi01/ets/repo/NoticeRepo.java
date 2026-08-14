package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Notice;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NoticeRepo extends BaseRepo<Notice> {

    public NoticeRepo(RedisStore store) {
        super(store, Notice.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.notice(id);
    }

    public void saveNotice(Notice n) {
        save(n.getId(), n);
        store.zAdd(RedisKeys.simNotices(n.getSimId()), n.getId(), n.getCreatedAt());
    }

    public List<Notice> recentBySim(String simId, int limit) {
        return findAll(store.zRevRange(RedisKeys.simNotices(simId), 0, Math.max(limit - 1, 0)));
    }
}
