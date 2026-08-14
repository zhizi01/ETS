package com.github.zhizi01.ets.ws;

/**
 * WebSocket 主题命名与鉴权分类（对应 docs/API.md 第四节）。
 * 主题一律以 "." 分段，第一段为主题族，用于路由与鉴权判定。
 */
public final class WsTopics {

    public static final String QUOTE = "quote";
    public static final String DEPTH = "depth";
    public static final String TRADE = "trade";
    public static final String KLINE = "kline";
    public static final String LISTING = "listing";
    public static final String SIM = "sim";
    public static final String ORDER = "order";
    public static final String ACCOUNT = "account";
    public static final String NEGO = "nego";
    public static final String ADMIN = "admin";

    private WsTopics() {
    }

    /** 订阅该主题所需的权限级别 */
    public enum Scope {
        /** 任何连接都可订阅 */
        PUBLIC,
        /** 必须是 topic 中 accountId 的属主 */
        ACCOUNT,
        /** 必须是管理员 */
        ADMIN_ONLY,
        /** 不认识的主题 */
        UNKNOWN
    }

    // ── 主题构造 ────────────────────────────────────────────

    public static String quote(String simId, String instrumentKey) {
        return QUOTE + "." + simId + "." + instrumentKey;
    }

    public static String depth(String sessionId) {
        return DEPTH + "." + sessionId;
    }

    public static String trade(String sessionId) {
        return TRADE + "." + sessionId;
    }

    public static String kline(String simId, String instrumentKey, String interval) {
        return KLINE + "." + simId + "." + instrumentKey + "." + interval;
    }

    public static String listing(String simId, String instrumentKey) {
        return LISTING + "." + simId + "." + instrumentKey;
    }

    public static String sim(String simId) {
        return SIM + "." + simId;
    }

    public static String order(String accountId) {
        return ORDER + "." + accountId;
    }

    public static String account(String accountId) {
        return ACCOUNT + "." + accountId;
    }

    public static String nego(String accountId) {
        return NEGO + "." + accountId;
    }

    public static String adminDashboard(String simId) {
        return ADMIN + ".dashboard." + simId;
    }

    // ── 解析 ────────────────────────────────────────────────

    public static Scope scopeOf(String topic) {
        if (topic == null || topic.isBlank()) {
            return Scope.UNKNOWN;
        }
        String family = family(topic);
        return switch (family) {
            case QUOTE, DEPTH, TRADE, KLINE, LISTING, SIM -> Scope.PUBLIC;
            case ORDER, ACCOUNT, NEGO -> Scope.ACCOUNT;
            case ADMIN -> Scope.ADMIN_ONLY;
            default -> Scope.UNKNOWN;
        };
    }

    public static String family(String topic) {
        if (topic == null) {
            return "";
        }
        int i = topic.indexOf('.');
        return i < 0 ? topic : topic.substring(0, i);
    }

    /** order./account./nego. 主题携带的 accountId，其它主题返回 null */
    public static String accountIdOf(String topic) {
        if (scopeOf(topic) != Scope.ACCOUNT) {
            return null;
        }
        String rest = topic.substring(topic.indexOf('.') + 1);
        return rest.isBlank() ? null : rest;
    }

    /** quote./depth./kline. 等主题里的 simId 或 sessionId，用于订阅后回推首帧快照 */
    public static String segment(String topic, int index) {
        String[] parts = topic.split("\\.");
        return index < parts.length ? parts[index] : null;
    }

    /** 主题合法性：段数与族匹配，避免客户端用通配串塞爆注册表 */
    public static boolean valid(String topic) {
        if (topic == null || topic.isBlank() || topic.length() > 128) {
            return false;
        }
        String[] parts = topic.split("\\.");
        return switch (family(topic)) {
            case DEPTH, TRADE, SIM, ORDER, ACCOUNT, NEGO -> parts.length == 2 && !parts[1].isBlank();
            case QUOTE, LISTING -> parts.length == 3 && !parts[1].isBlank() && !parts[2].isBlank();
            case KLINE -> parts.length == 4;
            case ADMIN -> parts.length == 3;
            default -> false;
        };
    }
}
