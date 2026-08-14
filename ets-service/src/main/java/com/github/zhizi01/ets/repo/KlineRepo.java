package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.common.JsonUtils;
import com.github.zhizi01.ets.domain.model.Bar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * K 线以 ZSET 存放，score = bar.ts，member = bar 的 JSON。
 * 同一根 bar 更新时先按 score 删除旧成员再写入新成员。
 */
@Repository
@RequiredArgsConstructor
public class KlineRepo {

    /** 每个标的每个周期最多保留的 bar 数 */
    private static final int KEEP = 1500;

    private final RedisStore store;

    public void upsert(String simId, String instrumentKey, String interval, Bar bar) {
        String key = RedisKeys.kline(simId, instrumentKey, interval);
        List<String> existing = store.zRangeByScore(key, bar.getTs(), bar.getTs());
        if (!existing.isEmpty()) {
            store.zRem(key, existing.toArray(new String[0]));
        }
        store.zAdd(key, JsonUtils.toJson(bar), bar.getTs());
        trim(key);
    }

    /** 取最近 limit 根，按时间升序返回（图表需要正序） */
    public List<Bar> recent(String simId, String instrumentKey, String interval, int limit) {
        String key = RedisKeys.kline(simId, instrumentKey, interval);
        List<String> raw = store.zRevRange(key, 0, Math.max(limit - 1, 0));
        List<Bar> bars = new ArrayList<>(raw.size());
        for (int i = raw.size() - 1; i >= 0; i--) {
            Bar b = JsonUtils.fromJson(raw.get(i), Bar.class);
            if (b != null) {
                bars.add(b);
            }
        }
        return bars;
    }

    public Bar lastBar(String simId, String instrumentKey, String interval) {
        List<String> raw = store.zRevRange(RedisKeys.kline(simId, instrumentKey, interval), 0, 0);
        return raw.isEmpty() ? null : JsonUtils.fromJson(raw.get(0), Bar.class);
    }

    public Bar barAt(String simId, String instrumentKey, String interval, long ts) {
        List<String> raw = store.zRangeByScore(RedisKeys.kline(simId, instrumentKey, interval), ts, ts);
        return raw.isEmpty() ? null : JsonUtils.fromJson(raw.get(0), Bar.class);
    }

    /** 取最近 n 根的收盘价，供机器人计算动量 */
    public List<Double> recentCloses(String simId, String instrumentKey, String interval, int n) {
        List<Double> out = new ArrayList<>();
        for (Bar b : recent(simId, instrumentKey, interval, n)) {
            out.add(b.getClose());
        }
        out.removeIf(Objects::isNull);
        return out;
    }

    private void trim(String key) {
        long size = store.zCard(key);
        if (size > KEEP + 200) {
            List<String> stale = store.zRange(key, 0, size - KEEP - 1);
            if (!stale.isEmpty()) {
                store.zRem(key, stale.toArray(new String[0]));
            }
        }
    }
}
