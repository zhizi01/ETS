package com.github.zhizi01.ets.api;

/** 审计留痕。实现方：管理模块。任何模块都可以调用，失败不影响主流程。 */
public interface AuditPort {

    void log(String simId, String actorType, String actorId, String actorName,
             String action, String target, String detail);

    default void system(String simId, String action, String target, String detail) {
        log(simId, "SYSTEM", null, "系统", action, target, detail);
    }
}
