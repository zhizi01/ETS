package com.github.zhizi01.ets.ws;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.MarketPort;
import com.github.zhizi01.ets.common.JsonUtils;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原生 WebSocket 端点 /ws 的报文处理器（前端是手写客户端，不走 STOMP）。
 * <p>
 * 上行：{"op":"sub|unsub","topics":[...]} 与 {"op":"ping"}。
 * 未携带有效令牌的连接同样允许建立，但只能订阅公开主题。
 */
@Slf4j
@Component
public class EtsWebSocketHandler extends TextWebSocketHandler {

    /** 前端每 20s 发一次 ping，这里给三倍冗余 */
    private static final long IDLE_TIMEOUT_MS = 60_000;
    /** 单连接订阅上限，防止恶意刷订阅撑爆注册表 */
    private static final int MAX_TOPICS_PER_SESSION = 200;

    private static final TypeReference<Map<String, Object>> MSG_TYPE = new TypeReference<>() {
    };

    private final WsSessionRegistry registry;
    private final WsBroadcaster broadcaster;
    private final AccountPort accountPort;
    private final MarketPort marketPort;

    public EtsWebSocketHandler(WsSessionRegistry registry,
                               WsBroadcaster broadcaster,
                               @Lazy AccountPort accountPort,
                               @Lazy MarketPort marketPort) {
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.accountPort = accountPort;
        this.marketPort = marketPort;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = registry.register(session);
        LoginUser lu = WsSessionRegistry.loginUserOf(safe);
        log.debug("WS 建立连接 {} user={} 在线 {}", session.getId(),
                lu == null ? "匿名" : lu.getUsername(), registry.onlineCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        registry.touch(session.getId());
        WebSocketSession safe = safeOf(session);

        Map<String, Object> msg;
        try {
            msg = JsonUtils.fromJson(message.getPayload(), MSG_TYPE);
        } catch (Exception e) {
            broadcaster.sendError(safe, "报文不是合法 JSON");
            return;
        }
        if (msg == null) {
            broadcaster.sendError(safe, "报文为空");
            return;
        }

        String op = String.valueOf(msg.getOrDefault("op", ""));
        switch (op) {
            case "ping" -> broadcaster.sendPong(safe);
            case "sub" -> doSubscribe(safe, topicsOf(msg));
            case "unsub" -> doUnsubscribe(safe, topicsOf(msg));
            default -> broadcaster.sendError(safe, "不支持的操作: " + op);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输异常 {}: {}", session.getId(), exception.getMessage());
        registry.unregister(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
        log.debug("WS 断开 {} status={} 在线 {}", session.getId(), status.getCode(), registry.onlineCount());
    }

    /** 60s 没有任何上行报文即认为客户端已失联，主动断开释放订阅 */
    @Scheduled(fixedDelay = 15_000)
    public void sweepIdleSessions() {
        for (String id : registry.idleSessionIds(IDLE_TIMEOUT_MS)) {
            WebSocketSession s = registry.session(id);
            if (s == null) {
                registry.unregister(id);
                continue;
            }
            log.debug("WS 心跳超时断开 {}", id);
            broadcaster.close(s, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    // ── 订阅处理 ────────────────────────────────────────────

    private void doSubscribe(WebSocketSession session, List<String> topics) {
        if (topics.isEmpty()) {
            broadcaster.sendError(session, "topics 不能为空");
            return;
        }
        LoginUser lu = WsSessionRegistry.loginUserOf(session);
        List<String> accepted = new ArrayList<>(topics.size());
        for (String topic : topics) {
            if (!WsTopics.valid(topic)) {
                broadcaster.sendError(session, "非法主题: " + topic);
                continue;
            }
            if (registry.topicsOf(session.getId()).size() >= MAX_TOPICS_PER_SESSION) {
                broadcaster.sendError(session, "订阅主题数已达上限");
                break;
            }
            String denied = denyReason(topic, lu);
            if (denied != null) {
                // 鉴权不过只回错误报文，不断连：前端可能同时订阅了公开与私有主题
                broadcaster.sendError(session, denied);
                continue;
            }
            registry.subscribe(session.getId(), topic);
            accepted.add(topic);
        }
        if (!accepted.isEmpty()) {
            broadcaster.sendTopics(session, WsBroadcaster.TYPE_SUBSCRIBED, accepted);
            accepted.forEach(t -> pushSnapshot(session, t));
        }
    }

    private void doUnsubscribe(WebSocketSession session, List<String> topics) {
        for (String topic : topics) {
            registry.unsubscribe(session.getId(), topic);
        }
        broadcaster.sendTopics(session, WsBroadcaster.TYPE_UNSUBSCRIBED, topics);
    }

    /** 返回 null 表示放行，否则为中文拒绝原因 */
    private String denyReason(String topic, LoginUser lu) {
        WsTopics.Scope scope = WsTopics.scopeOf(topic);
        switch (scope) {
            case PUBLIC:
                return null;
            case ADMIN_ONLY:
                return lu != null && lu.isAdmin() ? null : "无权订阅该主题，需要管理员身份";
            case ACCOUNT: {
                if (lu == null) {
                    return "无权订阅该主题，请先登录";
                }
                if (lu.isAdmin()) {
                    return null;
                }
                String accountId = WsTopics.accountIdOf(topic);
                try {
                    TradingAccount acct = accountPort.find(accountId);
                    if (acct == null) {
                        return "无权订阅该主题：交易账户不存在";
                    }
                    return lu.getId().equals(acct.getUserId()) ? null : "无权订阅该主题：该账户不属于当前用户";
                } catch (Exception e) {
                    log.debug("订阅鉴权查询账户失败 {}: {}", accountId, e.getMessage());
                    return "无权订阅该主题";
                }
            }
            default:
                return "未知主题: " + topic;
        }
    }

    /** 订阅成功后立刻回一帧当前快照，避免前端要等到下一次行情变化才有内容 */
    private void pushSnapshot(WebSocketSession session, String topic) {
        try {
            switch (WsTopics.family(topic)) {
                case WsTopics.QUOTE -> {
                    Object q = marketPort.quote(WsTopics.segment(topic, 1), WsTopics.segment(topic, 2));
                    if (q != null) {
                        broadcaster.sendTo(session, WsBroadcaster.TYPE_DATA, topic, q);
                    }
                }
                case WsTopics.DEPTH -> {
                    Object d = marketPort.depth(WsTopics.segment(topic, 1), 5);
                    if (d != null) {
                        broadcaster.sendTo(session, WsBroadcaster.TYPE_DATA, topic, d);
                    }
                }
                default -> {
                    // 其余主题没有天然快照，等待下一次事件推送
                }
            }
        } catch (Exception e) {
            log.debug("订阅首帧快照失败 {}: {}", topic, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> topicsOf(Map<String, Object> msg) {
        Object raw = msg.get("topics");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : (List<Object>) list) {
                if (o != null) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s.trim());
        }
        return out;
    }

    /** 推送必须走注册时的并发装饰器，否则多线程直写原始 session 会撕裂帧 */
    private WebSocketSession safeOf(WebSocketSession session) {
        WebSocketSession s = registry.session(session.getId());
        return s == null ? session : s;
    }
}
