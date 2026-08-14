package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.common.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Redis 访问门面。实体统一以 JSON 字符串存储在自己的 key 上，
 * 列表/排序则用 ZSET 存 id 索引，取详情时批量 MGET 回填。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStore {

    private final StringRedisTemplate redis;

    public StringRedisTemplate raw() {
        return redis;
    }

    // ── 实体 JSON ───────────────────────────────────────────

    public <T> T get(String key, Class<T> type) {
        String json = redis.opsForValue().get(key);
        return json == null ? null : JsonUtils.fromJson(json, type);
    }

    public void set(String key, Object value) {
        redis.opsForValue().set(key, JsonUtils.toJson(value));
    }

    public void set(String key, Object value, Duration ttl) {
        redis.opsForValue().set(key, JsonUtils.toJson(value), ttl);
    }

    public void setString(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    public String getString(String key) {
        return redis.opsForValue().get(key);
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public void delete(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    public void expire(String key, Duration ttl) {
        redis.expire(key, ttl);
    }

    /**
     * 批量取实体，自动跳过已失效的 id，保持入参顺序。
     */
    public <T> List<T> multiGet(Collection<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> keyList = new ArrayList<>(keys);
        List<String> values = redis.opsForValue().multiGet(keyList);
        if (values == null) {
            return new ArrayList<>();
        }
        List<T> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                try {
                    out.add(JsonUtils.fromJson(v, type));
                } catch (Exception e) {
                    log.warn("反序列化失败，已跳过: {}", e.getMessage());
                }
            }
        }
        return out;
    }

    // ── ZSET 索引 ───────────────────────────────────────────

    public void zAdd(String key, String member, double score) {
        redis.opsForZSet().add(key, member, score);
    }

    public void zRem(String key, String... members) {
        if (members != null && members.length > 0) {
            redis.opsForZSet().remove(key, (Object[]) members);
        }
    }

    public long zCard(String key) {
        Long n = redis.opsForZSet().size(key);
        return n == null ? 0 : n;
    }

    /** 按 score 升序取全部成员 */
    public List<String> zRange(String key) {
        return toList(redis.opsForZSet().range(key, 0, -1));
    }

    public List<String> zRange(String key, long start, long end) {
        return toList(redis.opsForZSet().range(key, start, end));
    }

    /** 按 score 降序取（最新在前） */
    public List<String> zRevRange(String key, long start, long end) {
        return toList(redis.opsForZSet().reverseRange(key, start, end));
    }

    public List<String> zRevRangeAll(String key) {
        return toList(redis.opsForZSet().reverseRange(key, 0, -1));
    }

    public List<String> zRangeByScore(String key, double min, double max) {
        return toList(redis.opsForZSet().rangeByScore(key, min, max));
    }

    public Double zScore(String key, String member) {
        return redis.opsForZSet().score(key, member);
    }

    /** 返回排名（0 起，score 降序），不存在返回 -1 */
    public long zRevRank(String key, String member) {
        Long r = redis.opsForZSet().reverseRank(key, member);
        return r == null ? -1 : r;
    }

    public List<ZSetOperations.TypedTuple<String>> zRevRangeWithScores(String key, long start, long end) {
        Set<ZSetOperations.TypedTuple<String>> s = redis.opsForZSet().reverseRangeWithScores(key, start, end);
        return s == null ? new ArrayList<>() : new ArrayList<>(s);
    }

    // ── SET ─────────────────────────────────────────────────

    public void sAdd(String key, String... members) {
        if (members != null && members.length > 0) {
            redis.opsForSet().add(key, members);
        }
    }

    public void sRem(String key, String... members) {
        if (members != null && members.length > 0) {
            redis.opsForSet().remove(key, (Object[]) members);
        }
    }

    public Set<String> sMembers(String key) {
        Set<String> s = redis.opsForSet().members(key);
        return s == null ? new LinkedHashSet<>() : s;
    }

    public boolean sIsMember(String key, String member) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key, member));
    }

    public long sCard(String key) {
        Long n = redis.opsForSet().size(key);
        return n == null ? 0 : n;
    }

    // ── HASH ────────────────────────────────────────────────

    public void hSet(String key, String field, String value) {
        redis.opsForHash().put(key, field, value);
    }

    public void hSetJson(String key, String field, Object value) {
        redis.opsForHash().put(key, field, JsonUtils.toJson(value));
    }

    public String hGet(String key, String field) {
        Object v = redis.opsForHash().get(key, field);
        return v == null ? null : v.toString();
    }

    public <T> T hGetJson(String key, String field, Class<T> type) {
        String v = hGet(key, field);
        return v == null ? null : JsonUtils.fromJson(v, type);
    }

    public void hDel(String key, String... fields) {
        if (fields != null && fields.length > 0) {
            redis.opsForHash().delete(key, (Object[]) fields);
        }
    }

    public Map<String, String> hGetAll(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }

    public <T> List<T> hValuesJson(String key, Class<T> type) {
        return hGetAll(key).values().stream()
                .filter(Objects::nonNull)
                .map(v -> JsonUtils.fromJson(v, type))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public long hIncr(String key, String field, long delta) {
        return redis.opsForHash().increment(key, field, delta);
    }

    // ── 计数 / 原子 ─────────────────────────────────────────

    public long incr(String key) {
        Long n = redis.opsForValue().increment(key);
        return n == null ? 0 : n;
    }

    /** SET NX，返回是否抢到 */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
    }

    // ── 分布式锁 ────────────────────────────────────────────

    /**
     * 自旋获取锁并执行，未抢到返回 null。用于挂牌摘牌等需要串行化的临界区。
     */
    public <T> T withLock(String lockName, Duration ttl, long waitMs, Supplier<T> action) {
        String key = RedisKeys.lock(lockName);
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() <= deadline) {
            if (setIfAbsent(key, token, ttl)) {
                try {
                    return action.get();
                } finally {
                    String cur = redis.opsForValue().get(key);
                    if (token.equals(cur)) {
                        redis.delete(key);
                    }
                }
            }
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ── Pub/Sub ─────────────────────────────────────────────

    public void publish(String channel, String message) {
        redis.convertAndSend(channel, message);
    }

    // ── 维护 ────────────────────────────────────────────────

    /** 按前缀扫描 key（SCAN，不阻塞） */
    public Set<String> scan(String pattern, int limit) {
        Set<String> out = new LinkedHashSet<>();
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(pattern).count(500).build())) {
            while (cursor.hasNext() && out.size() < limit) {
                out.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("SCAN {} 失败: {}", pattern, e.getMessage());
        }
        return out;
    }

    /** 删除某个模拟盘的全部数据（管理端重置用） */
    public long deleteByPattern(String pattern) {
        long total = 0;
        Set<String> keys;
        do {
            keys = scan(pattern, 2000);
            if (!keys.isEmpty()) {
                Long n = redis.delete(keys);
                total += n == null ? 0 : n;
            }
        } while (keys.size() >= 2000);
        return total;
    }

    private List<String> toList(Set<String> set) {
        return set == null ? new ArrayList<>() : new ArrayList<>(set);
    }
}
