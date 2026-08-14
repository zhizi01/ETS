package com.github.zhizi01.ets.ws;

import com.github.zhizi01.ets.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接与订阅关系的内存注册表。
 * <p>
 * 原生 WebSocketSession 的 sendMessage 不是线程安全的（并发写会撕裂帧），
 * 因此这里在注册时统一套上 {@link ConcurrentWebSocketSessionDecorator}，
 * 之后所有推送都只经由装饰器，由它串行化发送并对慢客户端做缓冲上限保护。
 */
@Slf4j
@Component
public class WsSessionRegistry {

    /** 单连接发送缓冲上限，超过即判定为慢客户端并关闭，避免拖垮广播线程 */
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    public static final String ATTR_LOGIN_USER = "loginUser";

    /** topic -> 订阅它的连接 */
    private final Map<String, Set<String>> topicSubscribers = new ConcurrentHashMap<>();
    /** 反向索引：连接 -> 它订阅的 topic，断开时据此清理 */
    private final Map<String, Set<String>> sessionTopics = new ConcurrentHashMap<>();
    /** 连接 id -> 线程安全的会话装饰器 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 连接 id -> 最近一次收到上行报文的时间，用于心跳超时清理 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    private final AtomicLong totalConnected = new AtomicLong();

    // ── 连接生命周期 ────────────────────────────────────────

    /** 注册并返回可并发写入的会话包装 */
    public WebSocketSession register(WebSocketSession raw) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        sessions.put(raw.getId(), safe);
        sessionTopics.put(raw.getId(), ConcurrentHashMap.newKeySet());
        lastSeen.put(raw.getId(), System.currentTimeMillis());
        totalConnected.incrementAndGet();
        return safe;
    }

    public void unregister(String sessionId) {
        Set<String> topics = sessionTopics.remove(sessionId);
        if (topics != null) {
            for (String t : topics) {
                Set<String> subs = topicSubscribers.get(t);
                if (subs != null) {
                    subs.remove(sessionId);
                    if (subs.isEmpty()) {
                        topicSubscribers.remove(t);
                    }
                }
            }
        }
        sessions.remove(sessionId);
        lastSeen.remove(sessionId);
    }

    public WebSocketSession session(String sessionId) {
        return sessions.get(sessionId);
    }

    public Collection<WebSocketSession> allSessions() {
        return sessions.values();
    }

    public void touch(String sessionId) {
        lastSeen.put(sessionId, System.currentTimeMillis());
    }

    /** 超过 idleMs 没有任何上行报文的连接 id */
    public List<String> idleSessionIds(long idleMs) {
        long deadline = System.currentTimeMillis() - idleMs;
        return lastSeen.entrySet().stream()
                .filter(e -> e.getValue() < deadline)
                .map(Map.Entry::getKey)
                .toList();
    }

    public static LoginUser loginUserOf(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_LOGIN_USER);
        return v instanceof LoginUser lu ? lu : null;
    }

    // ── 订阅关系 ────────────────────────────────────────────

    public void subscribe(String sessionId, String topic) {
        if (!sessions.containsKey(sessionId)) {
            return;
        }
        topicSubscribers.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionTopics.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(topic);
    }

    public void unsubscribe(String sessionId, String topic) {
        Set<String> subs = topicSubscribers.get(topic);
        if (subs != null) {
            subs.remove(sessionId);
            if (subs.isEmpty()) {
                topicSubscribers.remove(topic);
            }
        }
        Set<String> mine = sessionTopics.get(sessionId);
        if (mine != null) {
            mine.remove(topic);
        }
    }

    /** 订阅了该主题且仍然打开的连接 */
    public List<WebSocketSession> subscribersOf(String topic) {
        Set<String> ids = topicSubscribers.get(topic);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(sessions::get).filter(java.util.Objects::nonNull).toList();
    }

    public boolean hasSubscribers(String topic) {
        Set<String> ids = topicSubscribers.get(topic);
        return ids != null && !ids.isEmpty();
    }

    public Set<String> topicsOf(String sessionId) {
        return Collections.unmodifiableSet(sessionTopics.getOrDefault(sessionId, Set.of()));
    }

    // ── 指标 ────────────────────────────────────────────────

    public int onlineCount() {
        return sessions.size();
    }

    public int topicCount() {
        return topicSubscribers.size();
    }

    public int subscriptionCount() {
        return sessionTopics.values().stream().mapToInt(Set::size).sum();
    }

    public long totalConnected() {
        return totalConnected.get();
    }
}
