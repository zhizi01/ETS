package com.github.zhizi01.ets.common;

import com.github.zhizi01.ets.repo.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis INCR 的业务主键生成器，形如 U000001 / S0001 / O00000123。
 * 另提供全局单调递增的撮合序号 seq，用于「时间优先」排序。
 */
@Component
@RequiredArgsConstructor
public class IdGen {

    public static final String USER = "user";
    public static final String ADMIN = "admin";
    public static final String SIM = "sim";
    public static final String ACCOUNT = "acct";
    public static final String TSESSION = "tsess";
    public static final String ORDER = "order";
    public static final String TRADE = "trade";
    public static final String LISTING = "listing";
    public static final String TAKE = "take";
    public static final String NEGO = "nego";
    public static final String SPOT_BID = "spotbid";
    public static final String CONTRACT = "contract";
    public static final String BILL = "bill";
    public static final String ROBOT = "robot";
    public static final String EVENT = "event";
    public static final String NOTICE = "notice";
    public static final String FLOW = "flow";
    public static final String LOG = "log";
    public static final String SEQ = "seq";

    private static final java.util.Map<String, String> PREFIX = java.util.Map.ofEntries(
            java.util.Map.entry(USER, "U"),
            java.util.Map.entry(ADMIN, "AD"),
            java.util.Map.entry(SIM, "S"),
            java.util.Map.entry(ACCOUNT, "A"),
            java.util.Map.entry(TSESSION, "T"),
            java.util.Map.entry(ORDER, "O"),
            java.util.Map.entry(TRADE, "TR"),
            java.util.Map.entry(LISTING, "L"),
            java.util.Map.entry(TAKE, "TK"),
            java.util.Map.entry(NEGO, "N"),
            java.util.Map.entry(SPOT_BID, "SB"),
            java.util.Map.entry(CONTRACT, "C"),
            java.util.Map.entry(BILL, "B"),
            java.util.Map.entry(ROBOT, "R"),
            java.util.Map.entry(EVENT, "E"),
            java.util.Map.entry(NOTICE, "NT"),
            java.util.Map.entry(FLOW, "F"),
            java.util.Map.entry(LOG, "LG"));

    private final StringRedisTemplate redis;

    /** 进程内兜底序号，Redis 不可用时保证撮合仍可继续。 */
    private final AtomicLong localSeq = new AtomicLong(System.currentTimeMillis());

    public String next(String entity) {
        long n = incr(entity);
        return PREFIX.getOrDefault(entity, entity.toUpperCase()) + String.format("%06d", n);
    }

    /**
     * 撮合序号：全局单调递增，同价位据此实现时间优先。
     */
    public long nextSeq() {
        try {
            Long n = redis.opsForValue().increment(RedisKeys.seq(SEQ));
            if (n != null) {
                return n;
            }
        } catch (Exception ignored) {
            // Redis 抖动时退化为本地序号，仍保持单调
        }
        return localSeq.incrementAndGet();
    }

    private long incr(String entity) {
        Long n = redis.opsForValue().increment(RedisKeys.seq(entity));
        return n == null ? localSeq.incrementAndGet() : n;
    }
}
