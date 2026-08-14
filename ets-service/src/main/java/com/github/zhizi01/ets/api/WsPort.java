package com.github.zhizi01.ets.api;

/** WebSocket 广播。实现方：推送模块。主题命名见 docs/API.md 第四节。 */
public interface WsPort {

    /**
     * 向订阅了该主题的连接推送一条消息。
     * 客户端收到的报文形如 {"type":"data","topic":...,"data":...,"ts":...}
     */
    void push(String topic, Object data);

    /** 推送自定义 type 的消息 */
    void push(String topic, String type, Object data);

    /** 当前在线连接数 */
    int onlineCount();
}
