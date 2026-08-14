package com.github.zhizi01.ets.repo;

/**
 * Redis Key 集中注册表（对应 docs/DESIGN.md 第 8 节）。
 * 禁止在业务代码中手写 key 字符串，一律经由此处。
 */
public final class RedisKeys {

    public static final String P = "ets:";

    private RedisKeys() {
    }

    // ── 序列 ────────────────────────────────────────────────
    public static String seq(String entity) {
        return P + "seq:" + entity;
    }

    // ── 账号与令牌 ──────────────────────────────────────────
    public static String user(String userId) {
        return P + "user:" + userId;
    }

    public static String idxUsername() {
        return P + "idx:user:username";
    }

    public static String userAll() {
        return P + "user:all";
    }

    public static String admin(String adminId) {
        return P + "admin:" + adminId;
    }

    public static String idxAdminUsername() {
        return P + "idx:admin:username";
    }

    public static String adminAll() {
        return P + "admin:all";
    }

    public static String token(String token) {
        return P + "token:" + token;
    }

    public static String userTokens(String userId) {
        return P + "user:" + userId + ":tokens";
    }

    // ── 模拟盘 ──────────────────────────────────────────────
    public static String sim(String simId) {
        return P + "sim:" + simId;
    }

    public static String simAll() {
        return P + "sim:all";
    }

    public static String simAccounts(String simId) {
        return P + "sim:" + simId + ":accounts";
    }

    public static String simSessions(String simId) {
        return P + "sim:" + simId + ":sessions";
    }

    public static String simRobots(String simId) {
        return P + "sim:" + simId + ":robots";
    }

    public static String simEvents(String simId) {
        return P + "sim:" + simId + ":events";
    }

    public static String simNotices(String simId) {
        return P + "sim:" + simId + ":notices";
    }

    public static String simRank(String simId) {
        return P + "sim:" + simId + ":rank";
    }

    public static String simFundamental(String simId) {
        return P + "sim:" + simId + ":fundamental";
    }

    public static String simInstruments(String simId) {
        return P + "sim:" + simId + ":instruments";
    }

    // ── 交易账户 ────────────────────────────────────────────
    public static String acct(String acctId) {
        return P + "acct:" + acctId;
    }

    public static String idxAcctCode(String simId) {
        return P + "idx:acct:code:" + simId;
    }

    public static String userAccts(String userId, String simId) {
        return P + "user:" + userId + ":accts:" + simId;
    }

    public static String acctPos(String acctId) {
        return P + "acct:" + acctId + ":pos";
    }

    public static String acctFlow(String acctId) {
        return P + "acct:" + acctId + ":flow";
    }

    public static String flow(String flowId) {
        return P + "flow:" + flowId;
    }

    // ── 场次 / 委托 / 成交 ──────────────────────────────────
    public static String tsess(String sessionId) {
        return P + "tsess:" + sessionId;
    }

    public static String order(String orderId) {
        return P + "order:" + orderId;
    }

    public static String acctOrders(String acctId) {
        return P + "acct:" + acctId + ":orders";
    }

    public static String sessOrders(String sessionId) {
        return P + "tsess:" + sessionId + ":orders";
    }

    public static String obBid(String sessionId) {
        return P + "ob:" + sessionId + ":bid";
    }

    public static String obAsk(String sessionId) {
        return P + "ob:" + sessionId + ":ask";
    }

    public static String trade(String tradeId) {
        return P + "trade:" + tradeId;
    }

    public static String sessTrades(String sessionId) {
        return P + "tsess:" + sessionId + ":trades";
    }

    public static String acctTrades(String acctId) {
        return P + "acct:" + acctId + ":trades";
    }

    // ── 挂牌摘牌 ────────────────────────────────────────────
    public static String listing(String listingId) {
        return P + "listing:" + listingId;
    }

    public static String sessListings(String sessionId) {
        return P + "tsess:" + sessionId + ":listings";
    }

    public static String acctListings(String acctId) {
        return P + "acct:" + acctId + ":listings";
    }

    public static String take(String takeId) {
        return P + "take:" + takeId;
    }

    public static String listingTakes(String listingId) {
        return P + "listing:" + listingId + ":takes";
    }

    // ── 双边协商 ────────────────────────────────────────────
    public static String nego(String negoId) {
        return P + "nego:" + negoId;
    }

    public static String acctNegos(String acctId) {
        return P + "acct:" + acctId + ":negos";
    }

    // ── 日前现货 ────────────────────────────────────────────
    public static String spotBid(String bidId) {
        return P + "spotbid:" + bidId;
    }

    public static String sessSpotBids(String sessionId) {
        return P + "tsess:" + sessionId + ":spotbids";
    }

    public static String sessClearing(String sessionId) {
        return P + "tsess:" + sessionId + ":clearing";
    }

    // ── 合同 / 结算 ─────────────────────────────────────────
    public static String contract(String contractId) {
        return P + "contract:" + contractId;
    }

    public static String acctContracts(String acctId) {
        return P + "acct:" + acctId + ":contracts";
    }

    public static String bill(String billId) {
        return P + "bill:" + billId;
    }

    public static String acctBills(String acctId) {
        return P + "acct:" + acctId + ":bills";
    }

    public static String simBills(String simId, String period) {
        return P + "sim:" + simId + ":bills:" + period;
    }

    // ── 行情 ────────────────────────────────────────────────
    public static String quote(String simId, String instrumentKey) {
        return P + "quote:" + simId + ":" + instrumentKey;
    }

    public static String kline(String simId, String instrumentKey, String interval) {
        return P + "kline:" + simId + ":" + instrumentKey + ":" + interval;
    }

    // ── 机器人 / 事件 / 公告 / 审计 / 锁 / 广播 ─────────────
    public static String robot(String robotId) {
        return P + "robot:" + robotId;
    }

    public static String event(String eventId) {
        return P + "event:" + eventId;
    }

    public static String notice(String noticeId) {
        return P + "notice:" + noticeId;
    }

    public static String auditIndex() {
        return P + "log:audit";
    }

    public static String log(String logId) {
        return P + "log:" + logId;
    }

    public static String lock(String name) {
        return P + "lock:" + name;
    }

    public static String pubWs() {
        return P + "pub:ws";
    }
}
