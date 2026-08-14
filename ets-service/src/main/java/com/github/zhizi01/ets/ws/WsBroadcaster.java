package com.github.zhizi01.ets.ws;

import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题广播。报文格式见 docs/API.md 第四节：
 * {"type":"data","topic":"...","data":{...},"ts":...}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsBroadcaster implements WsPort {

    public static final String TYPE_DATA = "data";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_SUBSCRIBED = "subscribed";
    public static final String TYPE_UNSUBSCRIBED = "unsubscribed";
    public static final String TYPE_ERROR = "error";

    private final WsSessionRegistry registry;

    @Override
    public void push(String topic, Object data) {
        push(topic, TYPE_DATA, data);
    }

    @Override
    public void push(String topic, String type, Object data) {
        if (topic == null) {
            return;
        }
        List<WebSocketSession> targets = registry.subscribersOf(topic);
        if (targets.isEmpty()) {
            return;
        }
        String json;
        try {
            json = JsonUtils.toJson(envelope(type, topic, data));
        } catch (Exception e) {
            log.warn("行情推送序列化失败 topic={}: {}", topic, e.getMessage());
            return;
        }
        TextMessage msg = new TextMessage(json);
        List<String> dead = null;
        for (WebSocketSession s : targets) {
            if (!sendQuietly(s, msg)) {
                if (dead == null) {
                    dead = new ArrayList<>(2);
                }
                dead.add(s.getId());
            }
        }
        if (dead != null) {
            // 已关闭或写失败的连接立即摘除，避免下一轮继续对着死连接广播
            dead.forEach(registry::unregister);
        }
    }

    @Override
    public int onlineCount() {
        return registry.onlineCount();
    }

    // ── 单连接直发（握手应答、订阅回执、错误提示） ──────────

    public void sendTo(WebSocketSession session, String type, String topic, Object data) {
        sendQuietly(session, new TextMessage(JsonUtils.toJson(envelope(type, topic, data))));
    }

    public void sendPong(WebSocketSession session) {
        Map<String, Object> m = new LinkedHashMap<>(2);
        m.put("type", TYPE_PONG);
        m.put("ts", System.currentTimeMillis());
        sendQuietly(session, new TextMessage(JsonUtils.toJson(m)));
    }

    public void sendTopics(WebSocketSession session, String type, List<String> topics) {
        Map<String, Object> m = new LinkedHashMap<>(3);
        m.put("type", type);
        m.put("topics", topics);
        m.put("ts", System.currentTimeMillis());
        sendQuietly(session, new TextMessage(JsonUtils.toJson(m)));
    }

    public void sendError(WebSocketSession session, String msg) {
        Map<String, Object> m = new LinkedHashMap<>(3);
        m.put("type", TYPE_ERROR);
        m.put("msg", msg);
        m.put("ts", System.currentTimeMillis());
        sendQuietly(session, new TextMessage(JsonUtils.toJson(m)));
    }

    public void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 关闭失败说明连接已经没了，注册表清理即可
        } finally {
            registry.unregister(session.getId());
        }
    }

    private Map<String, Object> envelope(String type, String topic, Object data) {
        Map<String, Object> m = new LinkedHashMap<>(4);
        m.put("type", type == null ? TYPE_DATA : type);
        m.put("topic", topic);
        m.put("data", data);
        m.put("ts", System.currentTimeMillis());
        return m;
    }

    /** 返回 false 表示该连接已不可用 */
    private boolean sendQuietly(WebSocketSession session, TextMessage msg) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(msg);
            return true;
        } catch (Exception e) {
            log.debug("推送失败，摘除连接 {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }
}
