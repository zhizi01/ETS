package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.common.JsonUtils;
import com.github.zhizi01.ets.domain.model.SpotBid;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SpotBidRepo extends BaseRepo<SpotBid> {

    public SpotBidRepo(RedisStore store) {
        super(store, SpotBid.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.spotBid(id);
    }

    public void saveBid(SpotBid b) {
        save(b.getId(), b);
        store.zAdd(RedisKeys.sessSpotBids(b.getSessionId()), b.getId(), b.getCreatedAt());
    }

    public void update(SpotBid b) {
        b.setUpdatedAt(System.currentTimeMillis());
        save(b.getId(), b);
    }

    public List<SpotBid> listBySession(String sessionId) {
        return findAll(store.zRange(RedisKeys.sessSpotBids(sessionId)));
    }

    // ── 出清结果：hour -> {price, volume} ────────────────

    public void saveClearing(String sessionId, int hour, double price, double volume) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hour", hour);
        m.put("price", price);
        m.put("volume", volume);
        store.hSet(RedisKeys.sessClearing(sessionId), String.valueOf(hour), JsonUtils.toJson(m));
    }

    /** 返回 24 个小时段的出清结果，缺失的小时补 0 */
    public List<Map<String, Object>> clearingResult(String sessionId) {
        Map<String, String> raw = store.hGetAll(RedisKeys.sessClearing(sessionId));
        List<Map<String, Object>> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            String json = raw.get(String.valueOf(h));
            if (json == null) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("hour", h);
                empty.put("price", 0d);
                empty.put("volume", 0d);
                out.add(empty);
            } else {
                out.add(JsonUtils.fromJson(json, Map.class));
            }
        }
        return out;
    }

    public boolean hasClearing(String sessionId) {
        return store.exists(RedisKeys.sessClearing(sessionId));
    }
}
