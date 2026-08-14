package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 管理端操作与风控拒绝的审计留痕。 */
@Data
public class AuditLog {

    private String id;
    private String simId;
    /** ADMIN / USER / SYSTEM */
    private String actorType;
    private String actorId;
    private String actorName;
    private String action;
    private String target;
    private String detail;
    private String ip;
    private long createdAt;
}
