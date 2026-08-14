package com.github.zhizi01.ets.config;

import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.security.TokenService;
import com.github.zhizi01.ets.ws.EtsWebSocketHandler;
import com.github.zhizi01.ets.ws.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 原生 WebSocket 端点：ws://host/ws?token=xxx
 * 前端为手写客户端，不使用 STOMP。
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final EtsWebSocketHandler handler;
    private final TokenService tokenService;
    private final EtsProperties props;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = props.getCorsOrigins().isEmpty()
                ? new String[]{"http://localhost:5173", "http://localhost:5174",
                "http://127.0.0.1:5173", "http://127.0.0.1:5174"}
                : props.getCorsOrigins().toArray(new String[0]);
        registry.addHandler(handler, "/ws")
                .addInterceptors(new TokenHandshakeInterceptor(tokenService))
                .setAllowedOrigins(origins);
    }

    /**
     * 握手阶段解析 query 里的 token 并把 LoginUser 放进 session attributes。
     * 解析不到同样放行——匿名连接可以看公开行情，只是订阅私有主题时会被拒。
     */
    @RequiredArgsConstructor
    static class TokenHandshakeInterceptor implements HandshakeInterceptor {

        private final TokenService tokenService;

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = queryParam(request.getURI(), "token");
            try {
                LoginUser lu = tokenService.resolve(token);
                if (lu != null) {
                    attributes.put(WsSessionRegistry.ATTR_LOGIN_USER, lu);
                }
            } catch (Exception e) {
                log.debug("WS 握手解析令牌失败: {}", e.getMessage());
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // 无需处理
        }

        private String queryParam(URI uri, String name) {
            String query = uri == null ? null : uri.getQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0 && name.equals(pair.substring(0, i))) {
                    return java.net.URLDecoder.decode(pair.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }
}
