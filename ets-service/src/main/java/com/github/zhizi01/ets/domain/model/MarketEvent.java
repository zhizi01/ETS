package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.MarketEventType;
import lombok.Data;

/** 管理员注入的市场事件，会改变基本面并被机器人感知。 */
@Data
public class MarketEvent {

    private String id;
    private String simId;
    private MarketEventType type;
    /** 强度 -1 ~ 1，与 type.priceImpact 相乘得到实际冲击 */
    private double magnitude = 1.0;

    private String title;
    private String description;

    private long effectiveAt;
    private long expireAt;
    private String createdByAdminId;
    private long createdAt;

    public boolean active(long now) {
        return now >= effectiveAt && now < expireAt;
    }

    /** 对基准电价的相对冲击 */
    public double priceImpact() {
        return type == null ? 0 : type.getPriceImpact() * magnitude;
    }
}
